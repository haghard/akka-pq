package sample.blog

import com.datastax.driver.core.Row

//The idea from https://youtu.be/BfaBeT0pRe0?list=PLbZ2T3O9BuvczX5j03bWMrMFzK5OAs9mZ
object TypedDataset {

  trait Column[T] {
    self: T ⇒
    def name: String
    def check(row: Row): Boolean = !row.isNull(name)
  }

  sealed trait Id extends Column[Id]
  sealed trait Name extends Column[Name]
  sealed trait Score extends Column[Score]
  sealed trait ScoreD extends Column[ScoreD]

  object Id extends Id {
    override val name = "_id"
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

  case class TypedRow[T](row: Row) {

    def project[A <: Column[A]](c: Column[A]): TypedRow[T with A] = {
      assert(c.check(row))
      copy[T with A](row)
    }

  }

  def func[T <: Id with Name](ds: TypedRow[T]): TypedRow[T with Score] = ???

  //def func2[T <: Id with Name with Score](ds: Dataset[T]): Dataset[T] = ???

  val row: Row = ???
  val data = TypedRow[Id](row).project(Name).project(ScoreD)

  func(data)
  //func2(data) //compile error
}
