package sample.blog

//Example from Cats with Scala
//import sample.blog.Invariants3
object Invariants3 {

  import cats.Semigroup
  import cats.data.Validated
  import cats.data.Validated._ // for Valid and Invalid”
  import cats.syntax.semigroup._ //for |+|
  import cats.syntax.validated._ // for valid and invalid”
  import cats.syntax.apply._ // for mapN
  import cats.data._

  //import cats.syntax.cartesian._ // |@| syntax
  import cats.instances._

  type Errors = NonEmptyList[String]

  sealed trait Predicate[E, A] {

    import Predicate._

    def and(that: Predicate[E, A]): Predicate[E, A] =
      And(this, that)

    def or(that: Predicate[E, A]): Predicate[E, A] =
      Or(this, that)

    def apply(a: A)(implicit s: Semigroup[E]): Validated[E, A] =
      this match {
        case Pure(func) ⇒
          func(a)

        case And(left, right) ⇒
          (left(a), right(a)).mapN((_, _) ⇒ a)

        case Or(left, right) ⇒
          left(a) match {
            case Valid(a1) ⇒
              Valid(a)
            case Invalid(e1) ⇒
              right(a) match {
                case Valid(a2)   ⇒ Valid(a)
                case Invalid(e2) ⇒ Invalid(e1 |+| e2)
              }
          }
      }
  }

  object Predicate {

    final case class And[E, A](
        left: Predicate[E, A],
        right: Predicate[E, A]) extends Predicate[E, A]

    final case class Or[E, A](
        left: Predicate[E, A],
        right: Predicate[E, A]) extends Predicate[E, A]

    final case class Pure[E, A](
        func: A ⇒ Validated[E, A]) extends Predicate[E, A]

    def apply[E, A](f: A ⇒ Validated[E, A]): Predicate[E, A] =
      Pure(f)

    def lift[E, A](err: E, fn: A ⇒ Boolean): Predicate[E, A] =
      Pure(a ⇒ if (fn(a)) a.valid else err.invalid)
  }

  sealed trait Check[E, A, B] {

    import Check._

    def apply(in: A)(implicit s: Semigroup[E]): Validated[E, B]

    def map[C](f: B ⇒ C): Check[E, A, C] =
      Map[E, A, B, C](this, f)

    def flatMap[C](f: B ⇒ Check[E, A, C]): FlatMap[E, A, B, C] =
      FlatMap[E, A, B, C](this, f)

    def andThen[C](next: Check[E, B, C]): Check[E, A, C] =
      AndThen[E, A, B, C](this, next)
  }

  object Check {

    final case class Map[E, A, B, C](
        check: Check[E, A, B],
        func: B ⇒ C) extends Check[E, A, C] {

      def apply(a: A)(implicit s: Semigroup[E]): Validated[E, C] =
        check(a) map func
    }

    final case class FlatMap[E, A, B, C](
        check: Check[E, A, B],
        func: B ⇒ Check[E, A, C]) extends Check[E, A, C] {

      def apply(a: A)(implicit s: Semigroup[E]): Validated[E, C] =
        check(a).withEither(_.flatMap(b ⇒ func(b)(a).toEither))
    }

    final case class AndThen[E, A, B, C](
        check: Check[E, A, B],
        next: Check[E, B, C]) extends Check[E, A, C] {

      def apply(a: A)(implicit s: Semigroup[E]): Validated[E, C] =
        check(a).withEither(_.flatMap(b ⇒ next(b).toEither))
    }

    final case class Pure[E, A, B](
        func: A ⇒ Validated[E, B]) extends Check[E, A, B] {

      def apply(a: A)(implicit s: Semigroup[E]): Validated[E, B] =
        func(a)
    }

    final case class PurePredicate[E, A](
        pred: Predicate[E, A]) extends Check[E, A, A] {
      def apply(a: A)(implicit s: Semigroup[E]): Validated[E, A] =
        pred(a)
    }

    def apply[E, A](pred: Predicate[E, A]): Check[E, A, A] =
      PurePredicate(pred)

    def apply[E, A, B](func: A ⇒ Validated[E, B]): Check[E, A, B] =
      Pure(func)
  }

  def error(s: String): NonEmptyList[String] =
    NonEmptyList(s, Nil)

  def longerThan(n: Int): Predicate[Errors, String] =
    Predicate.lift(
      error(s"Must be longer than $n characters"),
      str ⇒ str.size > n)

  val alphanumeric: Predicate[Errors, String] =
    Predicate.lift(
      error(s"Must be all alphanumeric characters"),
      str ⇒ str.forall(_.isLetterOrDigit))

  val digit: Predicate[Errors, String] =
    Predicate.lift(
      error(s"Must contain at least one digit"),
      str ⇒ str.filter(_.isDigit).size > 0)

  def more(n: Int): Predicate[Errors, Int] =
    Predicate.lift(
      error(s"Must be > $n"),
      in ⇒ in > n)

  val checkUsername: Check[Errors, String, String] =
    Check(longerThan(5) and alphanumeric and digit)

  val checkUsername1: Check[Errors, String, String] =
    Check(longerThan(5) or alphanumeric)

  Check(longerThan(5)).andThen(Check(alphanumeric))("asdf")

  Check(more(5))(5)

  Check(longerThan(5)).andThen(Check(alphanumeric))

  Check(longerThan(5)).flatMap(_ ⇒ Check(alphanumeric))("asdf")

  //Check(longerThan(5)).flatMap(_ => Check(more(5)))("asdf")

  checkUsername("asdf@") //Invalid(NonEmptyList("Must be longer than 5 characters", List("Must be all alphanumeric characters")))

  checkUsername("asdf@") //Invalid(NonEmptyList("Must be longer than 3 characters", List()))

  checkUsername1("abcde@")

}
