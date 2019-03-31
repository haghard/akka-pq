package sample

import java.nio.ByteBuffer
import java.util.UUID

import com.datastax.driver.core.Row
import shapeless._

import scala.reflect.ClassTag
import scala.util.Try

import cats.data.{NonEmptyList, Validated}
import cats.implicits._
import cats.data.Validated._

package object blog {

  //persistence_id, partition_nr, sequence_nr, timestamp, timebucket, event
  //Schema
  val PersistenceId = Witness("persistence_id")
  val PartitionNr = Witness("partition_nr")
  val SequenceNr = Witness("sequence_nr")
  val timestamp = Witness("timestamp")
  val timebucket = Witness("timebucket")
  val Event = Witness("event")

  trait CCodec[DbType, JvmType] extends (Row => Validated[NonEmptyList[String], JvmType])

  trait ColumnCodec[ColumnName] {
    type DbType
    type JvmType

    def codec: CCodec[DbType, JvmType]
  }

  implicit val persistenceIdCodec = new ColumnCodec[PersistenceId.T] {
    override type DbType = String
    override type JvmType = String

    override def codec: CCodec[DbType, JvmType] =
      (r: Row) =>
        Try(r.getString(PersistenceId.value))
          .fold({ ex => Invalid(NonEmptyList.of(PersistenceId.value + ":" + ex.getMessage)) }, Valid(_))
  }

  implicit val partitionNrCodec = new ColumnCodec[PartitionNr.T] {
    override type DbType = Long
    override type JvmType = Long

    override def codec: CCodec[DbType, JvmType] = (r: Row) =>
      Try(r.getLong(PartitionNr.value))
        .fold({ ex => Invalid(NonEmptyList.of(PartitionNr.value + ":" + ex.getMessage)) }, Valid(_))
  }

  implicit val sequenceNrCodec = new ColumnCodec[SequenceNr.T] {
    override type DbType = Long
    override type JvmType = Long

    override def codec: CCodec[DbType, JvmType] = (r: Row) =>
      Try(r.getLong(SequenceNr.value))
        .fold({ ex => Invalid(NonEmptyList.of(SequenceNr.value + ":" + ex.getMessage)) }, Valid(_))
  }

  implicit val eventCodec = new ColumnCodec[Event.T] {
    override type DbType = ByteBuffer
    override type JvmType = Array[Byte]

    override def codec: CCodec[DbType, JvmType] = (r: Row) =>
      Try(r.getBytes(Event.value).array)
        .fold({ ex => Invalid(NonEmptyList.of(Event.value + ":" + ex.getMessage)) }, Valid(_))
  }


  trait Codec[A] {
    def apply(row: Row, fields: Vector[String], ind: Int): Option[A]
  }

  implicit object IntCodec extends Codec[Int] {
    override def apply(row: Row, fields: Vector[String], ind: Int): Option[Int] = {
      val field = fields(ind)
      Try(row.getInt(field)).toOption
    }
  }

  implicit object DoubleCodec extends Codec[Double] {
    override def apply(row: Row, fields: Vector[String], ind: Int): Option[Double] = {
      Try(row.getDouble(fields(ind))).toOption
    }
  }

  implicit object LongCodec extends Codec[Long] {
    override def apply(row: Row, fields: Vector[String], ind: Int): Option[Long] = {
      Try(row.getLong(fields(ind))).toOption
    }
  }

  implicit object StringCodec extends Codec[String] {
    override def apply(row: Row, fields: Vector[String], ind: Int): Option[String] = {
      Try(row.getString(fields(ind))).toOption
    }
  }

  implicit object DateCodec extends Codec[com.datastax.driver.core.LocalDate] {
    override def apply(row: Row, fields: Vector[String], ind: Int): Option[com.datastax.driver.core.LocalDate] = {
      Try(row.getDate(fields(ind))).toOption
    }
  }

  implicit object BytesCodec extends Codec[Array[Byte]] {
    override def apply(row: Row, fields: Vector[String], ind: Int): Option[Array[Byte]] = {
      Try(row.getBytes(fields(ind)).array).toOption
    }
  }

  implicit object UUIDCodec extends Codec[UUID] {
    override def apply(row: Row, fields: Vector[String], ind: Int): Option[UUID] = {
      Try(row.getUUID(fields(ind))).toOption
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
    (row: Row, fieldNames: Vector[String], ind: Int) =>
      fieldNames match {
        case _ +: _ ⇒
          for {
            //_ ← implicitly[Codec[H]].apply0(row, fields(acc))
            head ← implicitly[Codec[H]].apply(row, fieldNames, ind)
            tail ← implicitly[Codec[T]].apply(row, fieldNames, ind + 1)
          } yield head :: tail
      }

  //case class parser
  implicit def caseClassParser[A, R <: HList](implicit gen: Generic.Aux[A, R], c: Codec[R]): Codec[A] =
    (row: Row, fields: Vector[String], count: Int) =>
      c.apply(row, fields, count).map(gen.from)

  implicit class CassandraRecordSyntax(val row: Row) extends AnyVal {
    private def read[T](row: Row)(implicit c: ColumnCodec[T]): Validated[NonEmptyList[String], c.JvmType] =
      c.codec.apply(row)

    def asTuple: (String, Long, Long, Array[Byte]) = {
      //Using NonEmptyList to accumulate failures
      val validatedRow: Validated[NonEmptyList[String], (String, Long, Long, Array[Byte])] =
        (read[PersistenceId.T](row), read[PartitionNr.T](row),
          read[SequenceNr.T](row), read[Event.T](row)).mapN((_, _, _, _))

      validatedRow match {
        case Invalid(ers) =>
          val errors = "Journal errs: " + ers.mkString_("[", "], [", "]")
          println(errors)
          throw new Exception(errors)
        case Valid(tuple) =>
          tuple
      }
    }

    def as[T](implicit c: Codec[T], tag: ClassTag[T]): Option[T] = {
      val fields = tag.runtimeClass.getDeclaredFields.map(_.getName).toVector
      c(row, fields, 0)
    }


    //import scala.annotation.tailrec
    /*
        @tailrec
        final def loop(row: Row, ind: Int, limit: Int, acc: Array[Any]): Unit = {
          if (ind <= limit) {
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
          loop(row, 0, array.size - 1, Array[Any](fields.size))
          from(array)
        }*/
  }
}