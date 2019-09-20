package sample.blog

import cats.Applicative
import scala.reflect.ClassTag

//runMain sample.blog.Invariants
object Invariants {

  implicit val setApplicative: Applicative[Set] = new Applicative[Set] {
    override def pure[A](x: A): Set[A] = Set(x)

    override def ap[A, B](ff: Set[A ⇒ B])(fa: Set[A]): Set[B] =
      for { a ← fa; f ← ff } yield f(a)
  }

  trait Check[T, State] extends ((T, State) ⇒ Boolean) {
    def name: String
    def errorMessage(in: T, state: State): String
    def emptyInputMessage = s"$name. Empty input !"
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
    override def apply(in: String, state: Set[String]): Boolean =
      !state.contains(name)
    override def errorMessage(name: String, state: Set[String]) =
      s"$name is not unique"
  }

  class ContainsKey extends Check[Long, Map[Long, String]] {
    override val name = "Precondition: ContainsKey"
    override def apply(in: Long, state: Map[Long, String]): Boolean =
      state.get(in).isDefined
    override def errorMessage(in: Long, state: Map[Long, String]) =
      s"key $in is known"
  }

  class HasRoles extends Check[Set[Int], Set[Int]] {
    override val name = "Precondition: HasRoles"
    override def apply(ids: Set[Int], state: Set[Int]): Boolean =
      ids.filter(id ⇒ !state.contains(id)).isEmpty
    override def errorMessage(ids: Set[Int], state: Set[Int]) =
      s"User with roles [${ids.mkString(",")}] is not allowed to do this operation"
  }

  trait Catamorphism[F[_]] {
    def cata[A](in: F[A])(ifNull: ⇒ Either[String, F[A]], ifNotNull: A ⇒ Either[String, F[A]]): Either[String, F[A]]
  }

  implicit object OptionalCatamorphism extends Catamorphism[Option] {
    override def cata[A](in: Option[A])(
      ifNull: ⇒ Either[String, Option[A]],
      ifNotNull: A ⇒ Either[String, Option[A]]
    ): Either[String, Option[A]] = in.fold(ifNull)(ifNotNull(_))
  }

  implicit object IdCatamorphism extends Catamorphism[cats.Id] {
    override def cata[A](in: cats.Id[A])(
      ifNull: ⇒ Either[String, cats.Id[A]],
      ifNotNull: A ⇒ Either[String, cats.Id[A]]
    ): Either[String, cats.Id[A]] = if (in == null) ifNull else ifNotNull(in)
  }

  //: cats.Applicative
  object Precondition {
    private def create[T <: Check[_, _]: ClassTag, F[_]](ignore: Boolean): Precondition[F] = {
      new Precondition[F] {
        override val ignoreIfEmpty = ignore
        override def input[T, State](in: F[T], state: State)(implicit B: Check[T, State], C: Catamorphism[F]): Either[String, F[T]] = {
          val success: Either[String, F[T]] = Right(in)
          if (ignoreIfEmpty) {
            C.cata(in)(success, { input ⇒ if (B(input, state)) success else Left(B.errorMessage(input, state)) })
          } else {
            C.cata(in)(Left(B.emptyInputMessage), { input ⇒
              println(s"${B.name}[input: $input  ->  $state]")
              if (B(input, state)) success else Left(B.errorMessage(input, state))
            })
          }
        }
      }
    }

    def ignorable[T <: Check[_, _]: ClassTag, F[_]]: Precondition[F] =
      create(true)

    def apply[T <: Check[_, _]: ClassTag, F[_]]: Precondition[F] =
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
  def product[P <: Product, F, L <: HList, R](product: P)(f: F)(implicit G: Generic.Aux[P, L], fp: FnToProduct.Aux[F, L ⇒ R]): R = {
    val hlist = G to product
    f.toProduct(hlist)
  }

  implicit val aa = new ExistedId
  implicit val ab = new NameIsUnique
  implicit val ac = new HasRoles
  implicit val af = new ContainsKey

  case class ValidatedInput(a: Option[Long], b: Set[Int], c: String, d: Long)

  //http://typelevel.org/cats/typeclasses/applicative.html
  def main(args: Array[String]): Unit = {
    /*def eitherMonad[T]: cats.Monad[({type λ[α] = Either[T, α]})#λ] =
      new cats.Monad[({type λ[α] = Either[T, α]})#λ] {
        def unit[A](a: => A): Either[T, A] = Right(a)
        def flatMap[A,B](eea: Either[T, A])(f: A => Either[T, B]) = eea match {
          case Right(a) => f(a)
          case Left(e) => Left(e)
        }
      }
      */

    import cats.implicits._
    import cats.data.Validated._

    val a = Precondition.ignorable[ExistedId, Option].input(Some(8L), Set(103L, 4L, 78L, 32L, 8L, 1L)).toValidatedNel
    val b = Precondition.ignorable[HasRoles, cats.Id].input(Set(1, 9), Set(1, 3, 4, 5, 6, 7, 8)).toValidatedNel
    val c = Precondition.ignorable[NameIsUnique, cats.Id].input("bkl", Set("b", "c", "d", "e")).toValidatedNel
    val d = Precondition.ignorable[ContainsKey, cats.Id].input(4L, Map(5L -> "b", 6L -> "d", 7L -> "e")).toValidatedNel
    val out0 = cats.Traverse[List].sequence(List(a, b, c, d))

    out0.fold({ errors ⇒
      println("errors: " + errors.size + " : " + errors)
    }, { results ⇒
      println("results: " + results.size + " : " + results)
    })

    println("**************")

    //The whole pipeline stops if an error occurs
    val out =
      for {
        a ← Precondition.ignorable[ExistedId, Option].input(None /*Some(103L)*/ , Set(103L, 4L, 78L, 32L, 8L, 1L))

        b ← Precondition[HasRoles, cats.Id].input(Set(1, 8), Set(1, 3, 4, 5, 6, 7, 8))

        c ← Precondition[NameIsUnique, cats.Id].input("a", Set("b", "c", "d", "e"))

        d ← Precondition[ContainsKey, cats.Id].input(7L, Map(5L -> "b", 6L -> "d", 7L -> "e"))

        /*result = product(a, b, c, d) { (a: Option[Long], b: Set[Int], c: String, d: Long) ⇒
          ValidRes(a, b, c, d)
        }*/

      } yield {
        //(a, b, c, d)
        product(a, b, c, d)(ValidatedInput)
      }

    println(out)
  }
}