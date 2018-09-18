package sample

import java.util.UUID
import com.datastax.driver.core.Row
import shapeless._

import scala.reflect.ClassTag
import scala.util.Try

package object blog {

  trait Codec[A] {
    def apply(row: Row, fields: Vector[String], ind: Int): Option[A]
  }

  implicit object IntCodec extends Codec[Int] {
    override def apply(row: Row, fields: Vector[String], ind: Int): Option[Int] = {
      val field = fields(ind)
      Try(row.getInt(field)).fold({ _ => None }, Some(_))
    }
  }

  implicit object DoubleCodec extends Codec[Double] {
    override def apply(row: Row, fields: Vector[String], ind: Int): Option[Double] = {
      Try(row.getDouble(fields(ind))).map(Some(_)).getOrElse(None)
    }
  }

  implicit object LongCodec extends Codec[Long] {
    override def apply(row: Row, fields: Vector[String], ind: Int): Option[Long] = {
      Try(row.getLong(fields(ind))).map(Some(_)).getOrElse(None)
    }
  }

  implicit object StringCodec extends Codec[String] {
    override def apply(row: Row, fields: Vector[String], ind: Int): Option[String] = {
      Try(row.getString(fields(ind))).map(Some(_)).getOrElse(None)
    }
  }

  implicit object DateCodec extends Codec[com.datastax.driver.core.LocalDate] {
    override def apply(row: Row, fields: Vector[String], ind: Int): Option[com.datastax.driver.core.LocalDate] = {
      Try(row.getDate(fields(ind))).map(Some(_)).getOrElse(None)
    }
  }

  implicit object BytesCodec extends Codec[Array[Byte]] {
    override def apply(row: Row, fields: Vector[String], ind: Int): Option[Array[Byte]] = {
      Try(row.getBytes(fields(ind)).array).map(Some(_)).getOrElse(None)
    }
  }

  implicit object UUIDCodec extends Codec[UUID] {
    override def apply(row: Row, fields: Vector[String], ind: Int): Option[UUID] = {
      Try(row.getUUID(fields(ind))).map(Some(_)).getOrElse(None)
    }
  }

  implicit object HNilCodec extends Codec[HNil] {
    override def apply(row: Row, fields: Vector[String], ind: Int): Option[HNil] =
      if (fields.size >= ind) Some(HNil) else None
  }

  //https://docs.google.com/presentation/d/1AoVhMvawgXF3hxXL5omo_EPfvoO6t81cIQaautrpsFA/edit#slide=id.p
  /*
  implicit def inductionStep[H, T](implicit f: Reader[H], s: Reader[T]): Reader[H :: T] =
    (row: Row, fields: Vector[String], acc: Int) => {
      new Reader[H :: T] {
        implicitly[Reader[H]].apply(row, fields, acc) :: implicitly[Reader[T]].apply(row, fields, acc + 1)
      }
    }
*/

  //induction
  implicit def hlistParserCassandra[H: Codec, T <: HList : Codec]: Codec[H :: T] =
    (row: Row, fields: Vector[String], acc: Int) => {
      fields match {
        case h +: rest ⇒
          for {
            //_ ← implicitly[Codec[H]].apply0(row, fields(acc))
            head ← implicitly[Codec[H]].apply(row, fields, acc)
            tail ← implicitly[Codec[T]].apply(row, fields, acc + 1)
          } yield head :: tail

      }
    }

  //case class parser
  implicit def caseClassParser[A, R <: HList](implicit gen: Generic.Aux[A, R], c: Codec[R]): Codec[A] =
    (row: Row, fields: Vector[String], count: Int) =>
      c.apply(row, fields, count).map(gen.from)

  implicit class CassandraRecordOps(val row: Row) extends AnyVal {
    def as[T](implicit c: Codec[T], tag: ClassTag[T]): Option[T] = {
      val fields = tag.runtimeClass.getDeclaredFields.map(_.getName).toVector
      c(row, fields, 0)
    }

/*
    @tailrec
    private def loop(row: Row, ind: Int, limit: Int, acc: Array[Any]): Unit = {
      if(ind <= limit) {
        acc.update(ind, row.get[Any](ind, classOf[Any]))
        loop(row, ind + 1, limit, acc)
      } else ()
    }

    import shapeless._
    import ops.function._
    import shapeless.ops.traversable._
    import shapeless.syntax.std.traversable._
    import syntax.std.function._
    import ops.function._
    private def from[T, Repr <: HList](row: Array[Any])
                    (implicit gen: Generic.Aux[T, Repr], ft: FromTraversable[Repr]): Option[T] = {
      row.toHList[Repr].map(gen.from _)
    }

    def fromRow[T](implicit G: Generic[T], tag: ClassTag[T]): Option[T] = {
      //implicit val G = implicitly[Generic[T]]
      val fields = tag.runtimeClass.getDeclaredFields.map(_.getName).toVector
      val array = Array[Any](fields.size)
      loop(row, 0, array.size-1, Array[Any](fields.size))
      from(array)
    }
*/

  }
}