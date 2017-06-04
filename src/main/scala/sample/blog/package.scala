package sample

import java.util.UUID

import com.datastax.driver.core.Row
import shapeless._

import scala.reflect.ClassTag
import scala.util.Try

package object blog {

  trait Reader[A] {
    def apply(row: Row, fields: Vector[String], ind: Int): Option[A]
  }

  /*Try(row.getInt(field)).fold({ th =>
          Invalid(s"Couldn't parse $field to Int: ${th.getMessage} ").toValidatedNel
        }, { x => Valid(x).toValidatedNel })
   */
  implicit object IntReader extends Reader[Int] {
    override def apply(row: Row, fields: Vector[String], ind: Int): Option[Int] = {
      val field = fields(ind)
      Try(row.getInt(field)).fold({ _ => None }, Some(_))
    }
  }

  implicit object DoubleReader extends Reader[Double] {
    override def apply(row: Row, fields: Vector[String], ind: Int): Option[Double] = {
      Try(row.getDouble(fields(ind))).map(Some(_)).getOrElse(None)
    }
  }

  implicit object LongReader extends Reader[Long] {
    override def apply(row: Row, fields: Vector[String], ind: Int): Option[Long] = {
      Try(row.getLong(fields(ind))).map(Some(_)).getOrElse(None)
    }
  }

  implicit object StringReader extends Reader[String] {
    override def apply(row: Row, fields: Vector[String], ind: Int): Option[String] = {
      Try(row.getString(fields(ind))).map(Some(_)).getOrElse(None)
    }
  }

  implicit object DateReader extends Reader[com.datastax.driver.core.LocalDate] {
    override def apply(row: Row, fields: Vector[String], ind: Int): Option[com.datastax.driver.core.LocalDate] = {
      Try(row.getDate(fields(ind))).map(Some(_)).getOrElse(None)
    }
  }

  implicit object BytesReader extends Reader[Array[Byte]] {
    override def apply(row: Row, fields: Vector[String], ind: Int): Option[Array[Byte]] = {
      Try(row.getBytes(fields(ind)).array).map(Some(_)).getOrElse(None)
    }
  }

  implicit object UUIDReader extends Reader[UUID] {
    override def apply(row: Row, fields: Vector[String], ind: Int): Option[UUID] = {
      Try(row.getUUID(fields(ind))).map(Some(_)).getOrElse(None)
    }
  }

  implicit object HNilReader extends Reader[HNil] {
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
  implicit def hlistParserCassandra[H: Reader, T <: HList : Reader]: Reader[H :: T] =
    (row: Row, fields: Vector[String], acc: Int) => {
      fields match {
        case h +: rest ⇒
          for {
            head ← implicitly[Reader[H]].apply(row, fields, acc)
            tail ← implicitly[Reader[T]].apply(row, fields, acc + 1)
          } yield head :: tail
      }
    }

  //case class parser
  implicit def caseClassParser[A, R <: HList](implicit Gen: Generic.Aux[A, R], parser: Reader[R]): Reader[A] =
    (row: Row, fields: Vector[String], count: Int) => parser(row, fields, count).map(Gen.from)

  implicit class CassandraRecordOps(val row: Row) extends AnyVal {
    def as[T](implicit parser: Reader[T], tag: ClassTag[T]): Option[T] = {
      val fields = tag.runtimeClass.getDeclaredFields.map(_.getName).toVector
      parser(row, fields, 0)
    }
  }



  // Named induction step: (E, Tail)
  /*
    implicit def inductionStepNamed[E, Tail](implicit h: Reader[E], tail: Reader[Tail]) = new Reader[(E,Tail)] {
      h.apply()
      val name: String = s"${n.name}, ${tail.name}"
    }
  */

}