package sample.blog

object Invariants2 {

  import cats.Semigroup
  import cats.data.Validated
  import cats.data.Validated._
  import cats.syntax.semigroup._
  import cats.syntax.validated._
  //import cats.syntax.cartesian._ // |@| syntax
  import cats.instances._

  sealed trait Predicate[E, A] {
    import Predicate._

    def and(that: Predicate[E, A]): Predicate[E, A] =
      And(this, that)

    def or(that: Predicate[E, A]): Predicate[E, A] =
      Or(this, that)

    def apply(a: A)(implicit s: Semigroup[E]): Validated[E, A] =
      this match {
        case Pure(func) ⇒ func(a)
        case And(left, right) ⇒
          cats.Apply[Validated[E, *]].map2(left(a), right(a))((b, c) ⇒ a)
        //(left(a), right(a)).mapN { _ /*(_, _)*/ => a }
        case Or(left, right) ⇒
          left(a) match {
            case Valid(a1) ⇒ Valid(a)
            case Invalid(e1) ⇒
              right(a) match {
                case Valid(a2)   ⇒ Valid(a)
                case Invalid(e2) ⇒ Invalid(e1 |+| e2)
              }
          }
      }
  }

  object Predicate {
    final case class And[E, A](left: Predicate[E, A], right: Predicate[E, A]) extends Predicate[E, A]

    final case class Or[E, A](left: Predicate[E, A], right: Predicate[E, A]) extends Predicate[E, A]

    final case class Pure[E, A](func: A ⇒ Validated[E, A]) extends Predicate[E, A]

    def apply[E, A](f: A ⇒ Validated[E, A]): Predicate[E, A] =
      Pure(f)

    def lift[E, A](error: E, func: A ⇒ Boolean): Predicate[E, A] =
      Pure(a ⇒ if (func(a)) a.valid else error.invalid)
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
    final case class Map[E, A, B, C](check: Check[E, A, B], f: B ⇒ C) extends Check[E, A, C] {
      override def apply(a: A)(implicit s: Semigroup[E]): Validated[E, C] =
        check(a) map f
    }

    final case class FlatMap[E, A, B, C](check: Check[E, A, B], f: B ⇒ Check[E, A, C]) extends Check[E, A, C] {
      override def apply(a: A)(implicit s: Semigroup[E]): Validated[E, C] =
        check(a).withEither(_.flatMap(b ⇒ f(b)(a).toEither))
    }

    final case class AndThen[E, A, B, C](check: Check[E, A, B], next: Check[E, B, C]) extends Check[E, A, C] {
      override def apply(a: A)(implicit s: Semigroup[E]): Validated[E, C] =
        check(a).withEither {
          _.flatMap(b ⇒ next(b).toEither)
        }
    }

    final case class Pure[E, A, B](f: A ⇒ Validated[E, B]) extends Check[E, A, B] {
      override def apply(a: A)(implicit s: Semigroup[E]): Validated[E, B] = f(a)
    }

    final case class PurePredicate[E, A](p: Predicate[E, A]) extends Check[E, A, A] {
      def apply(a: A)(implicit s: Semigroup[E]): Validated[E, A] = p(a)
    }

    def apply[E, A](p: Predicate[E, A]): Check[E, A, A] = PurePredicate(p)

    def apply[E, A, B](func: A ⇒ Validated[E, B]): Check[E, A, B] =
      Pure(func)
  }

}
