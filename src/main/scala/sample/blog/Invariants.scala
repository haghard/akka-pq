package sample.blog

import cats.Applicative

import scala.reflect.ClassTag

//runMain sample.blog.Invariants
object Invariants {

  implicit val setApplicative: Applicative[Set] = new Applicative[Set] {
    override def pure[A](x: A): Set[A] = Set(x)

    override def ap[A, B](ff: Set[(A) => B])(fa: Set[A]): Set[B] =
      for {a <- fa; f <- ff} yield f(a)
  }

  trait Check[Input, State] {
    def name: String
    def apply(in: Input, on: State): Boolean
    def errorMessage(in: Input, state: State): String
    def emptyInputMessage = s"Empty input while checking binary logic thing $name"
  }

  sealed trait Precondition[F[_]] {
    def ignoreIfEmpty: Boolean
    def input[T, State](fa: F[T], state: State)(implicit CH: Check[T, State], C: Catamorphism[F]): Either[String, F[T]]
  }

  class ExistedId extends Check[Long, Set[Long]] {
    override val name = "Precondition: ExistedId"
    override def apply(in: Long, on: Set[Long]): Boolean = on.contains(in)
    override def errorMessage(in: Long, on: Set[Long]) = s"$in doesn't exists"
  }

  class NameIsUnique extends Check[String, Set[String]] {
    override val name = "Precondition: UniqueName"
    override def apply(in: String, state: Set[String]): Boolean = !state.contains(name)
    override def errorMessage(name: String, state: Set[String]) = s"$name is not unique"
  }

  class ContainsKey extends Check[Long, Map[Long, String]] {
    override val name = "Precondition: ContainsKey"
    override def apply(in: Long, state: Map[Long, String]): Boolean = state.get(in).isDefined
    override def errorMessage(in: Long, state: Map[Long, String]) = s"key $in is known"
  }

  class HasRoles extends Check[Set[Int], Set[Int]] {
    override val name = "Precondition: HasRoles"
    override def apply(ids: Set[Int], state: Set[Int]) = ids.filter(id => !state.contains(id)).isEmpty
    override def errorMessage(ids: Set[Int], state: Set[Int]) = s"User with roles [${ids.mkString(",")}] is not allowed to do this operation"
  }

  trait Catamorphism[F[_]] {
    def cata[A](opt: F[A])(ifNull: ⇒ Either[String, F[A]], ifNotNull: A ⇒ Either[String, F[A]]): Either[String, F[A]]
  }

  implicit object OptionalCatamorphism extends Catamorphism[Option] {
    override def cata[A](opt: Option[A])(
      ifNull: ⇒ Either[String, Option[A]],
      ifNotNull: (A) ⇒ Either[String, Option[A]]
    ): Either[String, Option[A]] = opt.fold(ifNull)(ifNotNull(_))
  }

  implicit object IdCatamorphism extends Catamorphism[cats.Id] {
    override def cata[A](opt: cats.Id[A])(
      ifNull: ⇒ Either[String, cats.Id[A]],
      ifNotNull: (A) ⇒ Either[String, cats.Id[A]]
    ): Either[String, cats.Id[A]] = if (opt == null) ifNull else ifNotNull(opt)
  }

  //: cats.Applicative
  object Precondition {
    private def create[T <: Check[_, _] : ClassTag, F[_]](ignore: Boolean) = {
      new Precondition[F] {
        override val ignoreIfEmpty = ignore
        override def input[T, State](in: F[T], state: State)
          (implicit B: Check[T, State], C: Catamorphism[F]): Either[String, F[T]] = {
          val success: Either[String, F[T]] = Right(in) //we want to return in here !!!
          if (ignoreIfEmpty) {
            C.cata(in)(success, { input => if (B(input, state)) success else Left(B.errorMessage(input, state)) })
          } else {
            C.cata(in)(Left(B.emptyInputMessage), { input =>
              println(s"${B.name}[input: $input  ->  $state]")
              if (B(input, state)) success else Left(B.errorMessage(input, state))
            })
          }
        }
      }
    }

    def ignorable[T <: Check[_, _] : ClassTag, F[_]]: Precondition[F] =
      create(true)

    def apply[T <: Check[_, _] : ClassTag, F[_]]: Precondition[F] =
      create(false)
  }


  import shapeless._
  import ops.function._
  import syntax.std.function._

  /**
   *
   * P is a Product, that is a tuple or a case class
   * F is an unconstrained type parameter
   * L is an HList
   * R is an unconstrained type parameter
   *
   * Generic.Aux[P, L]; this is the built-in “predicate” that Shapeless provides to encode the relation between a product type P and an HList L.
   * It holds when it is possible to derive a Generic[P] instance that converts P into L
   *
   * FnToProduct.Aux[F, L => R]; is he built-in “predicate” that Shapeless provides to encode the relation that holds
   * when F can be converted into a function from HList L to return type R; it holds when it is possible
   * to derive an FnToProduct[F] instance called that converts F into L => R
   *
   * HLists can be seen as an alternative implementation of the concept of Tuple or more generally, of the concept of Product.
   *
   * Abstracting over arity
   */
  def product[P <: Product, F, L <: HList, R](p: P)(f: F)
    (implicit generic: Generic.Aux[P, L], fp: FnToProduct.Aux[F, L => R]): R = {
    val hlist = generic to p
    f.toProduct(hlist)
  }

  //http://typelevel.org/cats/typeclasses/applicative.html
  def main(args: Array[String]): Unit = {
    implicit val aa = new ExistedId
    implicit val ab = new NameIsUnique
    implicit val ac = new HasRoles
    implicit val af = new ContainsKey
    val success = Right(1)

    /*def eitherMonad[T]: cats.Monad[({type λ[α] = Either[T, α]})#λ] =
      new cats.Monad[({type λ[α] = Either[T, α]})#λ] {
        def unit[A](a: => A): Either[T, A] = Right(a)
        def flatMap[A,B](eea: Either[T, A])(f: A => Either[T, B]) = eea match {
          case Right(a) => f(a)
          case Left(e) => Left(e)
        }
      }
      */

     /*Precondition[ExistedId, Option].input(Some(1037l), Set(103l, 4l, 78l, 32l, 8l, 1l)).flatMap { a =>
      Precondition[HasRoles, cats.Id].input(Set(1, 7), Set(1, 3, 4, 5, 6, 7, 8)).map { b =>
        (a,b)
      }
    }*/

    //The whole pipeline stops
    val out =
      for {
        a <- Precondition[ExistedId, Option].input(Some(103l), Set(103l, 4l, 78l, 32l, 8l, 1l))
          .fold(Left(_), { _ => success })

        b <- Precondition[HasRoles, cats.Id].input(Set(1, 7), Set(1, 3, 4, 5, 6, 7, 8))
          .fold(Left(_), { _ => success })

        c <- Precondition[NameIsUnique, cats.Id].input("bkl", Set("b", "c", "d", "e"))
          .fold(Left(_), { _ => success })

        d <- Precondition[ContainsKey, cats.Id].input(5l, Map(5l -> "b", 6l -> "d", 7l -> "e"))
          .fold(Left(_), { _ => success })

        e <- Right(78)

        result <- product(a, b, c, d, e) { (a: Int, b: Int, c: Int, d: Int, e: Int) =>
          Right(a + b + c + d + e)
        }
      } yield result

    println(out)
  }
}