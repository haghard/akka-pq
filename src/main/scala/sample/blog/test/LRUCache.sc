import scala.collection.mutable

class Node[T, U](var prev: Node[T, U] = null, var next: Node[T, U] = null,
  val key: T = null.asInstanceOf[T], val value: U = null.asInstanceOf[U])  {

  def apply(f: T => Unit): Unit = f(key)
}

object LRUCache {

  def apply[K, V](capacity: Int) = {
    val empty = new Node[K, V]()
    new LRUCache(capacity, new java.util.HashMap[K, Node[K, V]], empty, empty)
  }
}

final class LRUCache[K, V] private(
  capacity: Int,
  cache: java.util.HashMap[K, Node[K, V]],
  var lru: Node[K, V], var mru: Node[K, V]
) {

  private var currentSize: Int = 0

  def get(key: K): Option[V] = {
    val mruNode = cache.get(key)
    if (mruNode == null) None
    else if (mruNode.key == mru.key) Some(mru.value)
    else {
      val nextNode = mruNode.next
      val prevNode = mruNode.prev

      if (mruNode.key == lru.key) {
        nextNode.prev = null
        lru = nextNode
      } else {
        prevNode.next = nextNode
        nextNode.prev = prevNode
      }

      // Finally move our item to the MRU
      mruNode.prev = mru
      mru.next = mruNode
      mru = mruNode
      mru.next = null
      Some(mruNode.value)
    }
  }

  def put(key: K, value: V): Unit = {
    if (!cache.containsKey(key)) {
      val newNode = new Node[K, V](mru, null, key, value)
      mru.next = newNode
      cache.put(key, newNode)
      mru = newNode

      // Delete the left-most entry and update the LRU pointer
      if (capacity == currentSize) {
        cache.remove(lru.key)
        lru = lru.next
        lru.prev = null
      }
      // Update cache size, for the first added entry update the LRU pointer
      else if (currentSize < capacity) {
        if (currentSize == 0) {
          lru = newNode
        }
        currentSize += 1
      }
    }
  }

  def size: Int = currentSize

  override def toString: String = {
    def loopMap(it: java.util.Iterator[K], sb: mutable.StringBuilder,
      first: Boolean = false): String = {
      if (it.hasNext)
        if (first) loopMap(it, sb.append(it.next))
        else loopMap(it, sb.append(",").append(it.next))
      else sb.toString
    }

    def loopList(n: Node[K, V], sb: mutable.StringBuilder): String = {
      val sb0 = if (n.key != null && n.value != null) sb.append(n.key).append(",") else sb
      if (n.next != null) loopList(n.next, sb0)
      else sb.append(" - ").toString
    }

    loopList(lru, new mutable.StringBuilder().append("list:")) +
      loopMap(cache.keySet.iterator, new mutable.StringBuilder().append("cache:"), true)
  }
}


val c = LRUCache[Symbol, Int](6)

c.put('a, -1)
c.put('b, -1)
c.toString
c.size

c.put('c, -1)
c.put('d, -1)
c.put('e, -1)
c.put('f, -1)
c.toString
c.size


c.put('g, -1)
c.toString
c.size

c.get('e)

c.toString
c.size

c.get('g)
c.toString
c.size