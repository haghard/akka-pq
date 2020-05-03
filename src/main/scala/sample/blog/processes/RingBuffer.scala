package sample.blog.eg

import scala.reflect.ClassTag

object RingBuffer {
  def nextPowerOfTwo(value: Int): Int =
    1 << (32 - Integer.numberOfLeadingZeros(value - 1))
}

class RingBuffer[T: ClassTag] private (capacity: Int, mask: Int, buffer: Array[T]) {
  private var tail: Long = 0L
  private var head: Long = 0L

  def this(capacity: Int) {
    this(
      RingBuffer.nextPowerOfTwo(capacity),
      RingBuffer.nextPowerOfTwo(capacity) - 1,
      Array.ofDim[T](RingBuffer.nextPowerOfTwo(capacity)))
  }

  def offer(e: T): Boolean = {
    val wrapPoint = tail - capacity
    if (head <= wrapPoint) false
    else {
      val ind = (tail & mask).toInt
      buffer(ind) = e
      tail = tail + 1
      true
    }
  }

  def poll(): Option[T] =
    if (head >= tail) None
    else {
      val index = (head & mask).toInt
      val element: T = buffer(index)
      buffer(index) = null.asInstanceOf[T]
      head = head + 1
      Some(element)
    }

  def toArray: Array[T] = {
    var i = head
    var j = 0
    val copy = if (tail > capacity) Array.ofDim[T](capacity) else Array.ofDim[T](tail.toInt)
    while (i < tail) {
      copy(j) = buffer((i % capacity).toInt)
      j += 1
      i += 1
    }
    copy
  }

  def size = tail - head

  override def toString =
    s"[$head - $tail]: ${buffer.mkString(",")}"
}
