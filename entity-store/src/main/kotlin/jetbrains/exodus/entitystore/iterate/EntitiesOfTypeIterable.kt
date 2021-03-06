/**
 * Copyright 2010 - 2019 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jetbrains.exodus.entitystore.iterate

import jetbrains.exodus.bindings.LongBinding
import jetbrains.exodus.entitystore.*
import jetbrains.exodus.entitystore.util.EntityIdSetFactory
import jetbrains.exodus.env.Cursor

/**
 * Iterates all entities of specified entity type.
 */
open class EntitiesOfTypeIterable(txn: PersistentStoreTransaction, private val entityTypeId: Int) : EntityIterableBase(txn) {

    override fun getEntityTypeId() = entityTypeId

    override fun getIteratorImpl(txn: PersistentStoreTransaction) =
            EntitiesOfTypeIterator(this, store.getEntitiesIndexCursor(txn, entityTypeId))

    override fun nonCachedHasFastCountAndIsEmpty() = true

    override fun findLinks(entities: EntityIterable, linkName: String): EntityIterable {
        val txn = transaction
        val linkId = store.getLinkId(txn, linkName, false)
        if (linkId < 0) {
            return EntityIterableBase.EMPTY
        }
        val filter = (entities as EntityIterableBase).source
        val entitiesWithLink = EntitiesWithCertainLinkIterable(txn, entityTypeId, linkId)
        return object : EntityIterableBase(txn) {

            override fun isSortedById() = entitiesWithLink.isSortedById

            override fun canBeReordered() = true

            override fun getIteratorImpl(txn: PersistentStoreTransaction): EntityIterator {
                val idSet = filter.toSet(txn)
                val it = entitiesWithLink.iterator() as LinksIteratorWithTarget
                return object : EntityIteratorBase(this@EntitiesOfTypeIterable) {

                    private var distinctIds = EntityIdSetFactory.newSet()
                    private var id = nextAvailableId()

                    override fun hasNextImpl() = id != null

                    override fun nextIdImpl(): EntityId? {
                        val result = id
                        distinctIds = distinctIds.add(result)
                        id = nextAvailableId()
                        return result
                    }

                    private fun nextAvailableId(): EntityId? {
                        while (it.hasNext()) {
                            val next = it.nextId()
                            if (!distinctIds.contains(next) && idSet.contains(it.targetId)) {
                                return next
                            }
                        }
                        return null
                    }
                }.apply {
                    it.cursor?.let {
                        cursor = it
                    }
                }
            }

            override fun getHandleImpl(): EntityIterableHandle {
                return object : EntityIterableHandleDecorator(store, EntityIterableType.FILTER_LINKS, entitiesWithLink.handle) {

                    private val linkIds = mergeFieldIds(intArrayOf(linkId), filter.handle.linkIds)

                    override fun getLinkIds() = linkIds

                    override fun toString(builder: StringBuilder) {
                        super.toString(builder)
                        applyDecoratedToBuilder(builder)
                        builder.append('-')
                        (filter.handle as EntityIterableHandleBase).toString(builder)
                    }

                    override fun hashCode(hash: EntityIterableHandleBase.EntityIterableHandleHash) {
                        super.hashCode(hash)
                        hash.applyDelimiter()
                        hash.apply(filter.handle)
                    }

                    override fun isMatchedLinkAdded(source: EntityId, target: EntityId, linkId: Int) =
                            decorated.isMatchedLinkAdded(source, target, linkId) ||
                                    entities.handle.isMatchedLinkAdded(source, target, linkId)

                    override fun isMatchedLinkDeleted(source: EntityId, target: EntityId, id: Int) =
                            decorated.isMatchedLinkDeleted(source, target, id) ||
                                    entities.handle.isMatchedLinkDeleted(source, target, id)
                }
            }
        }
    }

    override fun isEmptyImpl(txn: PersistentStoreTransaction) = countImpl(txn) == 0L

    override fun getHandleImpl() = EntitiesOfTypeIterableHandle(this)

    override fun createCachedInstance(txn: PersistentStoreTransaction): CachedInstanceIterable =
            UpdatableEntityIdSortedSetCachedInstanceIterable(txn, this)

    override fun countImpl(txn: PersistentStoreTransaction) =
            store.getEntitiesTable(txn, entityTypeId).count(txn.environmentTransaction)

    class EntitiesOfTypeIterator(iterable: EntitiesOfTypeIterable,
                                 index: Cursor) : EntityIteratorBase(iterable) {

        private var hasNext = false
        private var hasNextValid = false
        private val entityTypeId = iterable.entityTypeId

        private val entityId: EntityId
            get() = PersistentEntityId(entityTypeId, LongBinding.compressedEntryToLong(cursor.key))

        init {
            cursor = index
        }

        public override fun hasNextImpl(): Boolean {
            if (!hasNextValid) {
                hasNext = cursor.next
                hasNextValid = true
            }
            return hasNext
        }

        public override fun nextIdImpl(): EntityId? {
            if (hasNextImpl()) {
                iterable.explain(EntityIterableType.ALL_ENTITIES)
                val result = entityId
                hasNextValid = false
                return result
            }
            return null
        }

        override fun getLast(): EntityId? {
            return if (!cursor.prev) {
                null
            } else entityId
        }
    }

    open class EntitiesOfTypeIterableHandle(source: EntitiesOfTypeIterable)
        : ConstantEntityIterableHandle(source.store, EntityIterableType.ALL_ENTITIES) {

        private val typeId: Int = source.entityTypeId

        override fun toString(builder: StringBuilder) {
            super.toString(builder)
            builder.append(entityTypeId)
        }

        override fun hashCode(hash: EntityIterableHandleBase.EntityIterableHandleHash) {
            hash.apply(entityTypeId)
        }

        override fun getEntityTypeId() = typeId

        override fun getTypeIdsAffectingCreation() = intArrayOf(entityTypeId)

        override fun isMatchedEntityAdded(added: EntityId) = added.typeId == entityTypeId

        override fun isMatchedEntityDeleted(deleted: EntityId) = deleted.typeId == entityTypeId

        override fun onEntityAdded(handleChecker: EntityAddedOrDeletedHandleChecker): Boolean {
            val iterable = PersistentStoreTransaction.getUpdatable(handleChecker, this, UpdatableEntityIdSortedSetCachedInstanceIterable::class.java)
            if (iterable != null) {
                iterable.addEntity(handleChecker.id)
                return true
            }
            return false
        }

        override fun onEntityDeleted(handleChecker: EntityAddedOrDeletedHandleChecker): Boolean {
            val iterable = PersistentStoreTransaction.getUpdatable(handleChecker, this, UpdatableEntityIdSortedSetCachedInstanceIterable::class.java)
            if (iterable != null) {
                iterable.removeEntity(handleChecker.id)
                return true
            }
            return false
        }
    }

    companion object {

        init {
            EntityIterableBase.registerType(EntityIterableType.ALL_ENTITIES) { txn, _, parameters ->
                EntitiesOfTypeIterable(txn, Integer.valueOf(parameters[0] as String))
            }
        }
    }
}
