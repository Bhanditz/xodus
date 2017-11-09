/**
 * Copyright 2010 - 2017 JetBrains s.r.o.
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
package jetbrains.exodus.query

import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityIterable
import jetbrains.exodus.kotlin.notNull
import java.util.*

abstract class InMemoryQueueSortIterable(source: Iterable<Entity>, comparator: Comparator<Entity>) : SortEngine.InMemorySortIterable(source, comparator) {

    abstract fun createQueue(unsorted: Collection<Entity>): Queue<Entity>

    override fun iterator(): MutableIterator<Entity> {
        val collection: Collection<Entity>
        if (source is EntityIterable) {
            collection = object : AbstractCollection<Entity>() {
                override val size: Int
                    get() = source.size().toInt()

                override fun iterator(): MutableIterator<Entity> {
                    return source.iterator()
                }
            }
        } else {
            collection = mutableListOf()
            source.forEach { collection.add(it) }
        }
        return object : MutableIterator<Entity> {

            private val queue = createQueue(collection)

            override fun hasNext(): Boolean {
                return queue.isNotEmpty()
            }

            override fun next(): Entity {
                return queue.poll().notNull { "Can't be" }
            }

            override fun remove() {
                throw UnsupportedOperationException()
            }
        }
    }
}