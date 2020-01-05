package sample.blog

import com.datastax.driver.core.Row

//The idea from https://youtu.be/BfaBeT0pRe0?list=PLbZ2T3O9BuvczX5j03bWMrMFzK5OAs9mZ
object TypedDataset {

  //Schema type

  trait Column[T <: Column[T]] { self: T ⇒
    def name: String
    def nonEmpty(row: Row): Boolean = !row.isNull(name)
  }

  sealed trait Id extends Column[Id]
  sealed trait Name extends Column[Name]
  sealed trait Score extends Column[Score]
  sealed trait ScoreD extends Column[ScoreD]

  object Id extends Id {
    override val name = "id"
  }

  object Name extends Name {
    override val name = "name"
  }

  object Score extends Score {
    override val name = "score"
  }

  object ScoreD extends ScoreD {
    override val name = "score_d"
  }


  object TypedRow {
    def id(row: Row): TypedRow[Id] = TypedRow[Id](rawRow)
  }

  final case class TypedRow[S](row: Row) {
    def |@|[T <: Column[T]](c: Column[T]): TypedRow[S with T] =
      project(c)

    def |+|[T <: Column[T]](c: Column[T]): TypedRow[S with T] =
      project(c)

    /**
     * Checks in runtime that the give column is there. Tracks types in compile time.
     */
    def project[T <: Column[T]](c: Column[T]): TypedRow[S with T] = {
      assert(c.nonEmpty(row))
      copy[S with T](row)
    }
  }

  /**
   * Needs a row with Id and Name fields
   */
  def transformation0[T <: Id with Name](ds: TypedRow[T]): TypedRow[T with Score] = ???

  def transformation1[T <: Id with Name with Score](ds: TypedRow[T]): TypedRow[T] = ???

  val rawRow: Row = ???
  val data = TypedRow.id(rawRow) |@| Name |@| ScoreD
  val data0 = TypedRow.id(rawRow) |+| Name |+| ScoreD

  transformation0(data)

  /*
  Compilation error
  found   : sample.blog.TypedDataset.TypedRow[sample.blog.TypedDataset.Id with sample.blog.TypedDataset.Name with sample.blog.TypedDataset.ScoreD]
  [error]  required: sample.blog.TypedDataset.TypedRow[T]
  */
  //transformation1(data)

}
