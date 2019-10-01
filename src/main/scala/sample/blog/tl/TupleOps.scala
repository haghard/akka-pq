package sample.blog.tl

import sample.blog.tl.BinaryPolyFunc.Case
import sample.blog.tl.TupleOps.{ AppendOne, FoldLeft }

/**
 * copied from akka-http:
 * https://github.com/akka/akka-http/blob/master/akka-http/src/main/scala/akka/http/scaladsl/server/util/TupleOps.scala
 */
object TupleOps {

  trait AppendOne[P, S] {
    type Out

    def apply(prefix: P, last: S): Out
  }

  object AppendOne extends TupleAppendOneInstances

  trait FoldLeft[In, T, Op] {
    type Out

    def apply(zero: In, tuple: T): Out
  }

  object FoldLeft extends TupleFoldInstances

  trait Join[P, S] {
    type Out

    def apply(prefix: P, suffix: S): Out
  }

  type JoinAux[P, S, O] = Join[P, S] { type Out = O }

  object Join extends LowLevelJoinImplicits {
    // O(1) shortcut for the Join[Unit, T] case to avoid O(n) runtime in this case
    implicit def join0P[T]: JoinAux[Unit, T, T] =
      new Join[Unit, T] {
        type Out = T

        def apply(prefix: Unit, suffix: T): Out = suffix
      }

    // we implement the join by folding over the suffix with the prefix as growing accumulator
    object Fold extends BinaryPolyFunc {
      implicit def step[T, A](implicit append: AppendOne[T, A]): BinaryPolyFunc.Case[T, A, Fold.type] { type Out = append.Out } =
        at[T, A](append(_, _))
    }

  }

  sealed abstract class LowLevelJoinImplicits {
    implicit def join[P, S](implicit fold: FoldLeft[P, S, Join.Fold.type]): JoinAux[P, S, fold.Out] =
      new Join[P, S] {
        type Out = fold.Out

        def apply(prefix: P, suffix: S): Out = fold(prefix, suffix)
      }
  }

}

/**
 * Allows the definition of binary poly-functions (e.g. for folding over tuples).
 *
 * Note: the poly-function implementation seen here is merely a stripped down version of
 * what Miles Sabin made available with his awesome shapeless library. All credit goes to him!
 */
trait BinaryPolyFunc {
  def at[A, B] = new CaseBuilder[A, B]

  class CaseBuilder[A, B] {
    def apply[R](f: (A, B) ⇒ R) = new BinaryPolyFunc.Case[A, B, BinaryPolyFunc.this.type] {
      type Out = R

      def apply(a: A, b: B) = f(a, b)
    }
  }
}

object BinaryPolyFunc {

  sealed trait Case[A, B, Op] {
    type Out
    def apply(a: A, b: B): Out
  }

}

abstract class TupleFoldInstances {

  type Aux[In, T, Op, Out0] = FoldLeft[In, T, Op] { type Out = Out0 }

  implicit def t0[In, Op]: Aux[In, Unit, Op, In] =
    new FoldLeft[In, Unit, Op] {
      type Out = In

      def apply(zero: In, tuple: Unit): In = zero
    }

  implicit def t1[In, A, Op](implicit f: Case[In, A, Op]): Aux[In, Tuple1[A], Op, f.Out] =
    new FoldLeft[In, Tuple1[A], Op] {
      type Out = f.Out

      def apply(zero: In, tuple: Tuple1[A]): f.Out = f(zero, tuple._1)
    }

  implicit def t2[In, T1, X, T2, Op](implicit fold: Aux[In, Tuple1[T1], Op, X], f: Case[X, T2, Op]): Aux[In, Tuple2[T1, T2], Op, f.Out] =
    new FoldLeft[In, Tuple2[T1, T2], Op] {
      type Out = f.Out

      def apply(zero: In, t: Tuple2[T1, T2]): f.Out =
        f(fold(zero, Tuple1(t._1)), t._2)
    }

  implicit def t3[In, T1, T2, X, T3, Op](implicit fold: Aux[In, Tuple2[T1, T2], Op, X], f: Case[X, T3, Op]): Aux[In, Tuple3[T1, T2, T3], Op, f.Out] =
    new FoldLeft[In, Tuple3[T1, T2, T3], Op] {
      type Out = f.Out

      def apply(zero: In, t: Tuple3[T1, T2, T3]) =
        f(fold(zero, Tuple2(t._1, t._2)), t._3)
    }
}

abstract class TupleAppendOneInstances {
  type Aux[P, S, Out0] = AppendOne[P, S] { type Out = Out0 }

  implicit def append0[T1]: Aux[Unit, T1, Tuple1[T1]] =
    new AppendOne[Unit, T1] {
      type Out = Tuple1[T1]

      def apply(prefix: Unit, last: T1): Tuple1[T1] = Tuple1(last)
    }

  implicit def append1[T1, L]: Aux[Tuple1[T1], L, Tuple2[T1, L]] =
    new AppendOne[Tuple1[T1], L] {
      type Out = Tuple2[T1, L]

      def apply(prefix: Tuple1[T1], last: L): Tuple2[T1, L] = Tuple2(prefix._1, last)
    }

  implicit def append2[T1, T2, L]: Aux[Tuple2[T1, T2], L, Tuple3[T1, T2, L]] =
    new AppendOne[Tuple2[T1, T2], L] {
      type Out = Tuple3[T1, T2, L]

      def apply(prefix: Tuple2[T1, T2], last: L): Tuple3[T1, T2, L] = Tuple3(prefix._1, prefix._2, last)
    }

  implicit def append3[T1, T2, T3, L]: Aux[Tuple3[T1, T2, T3], L, Tuple4[T1, T2, T3, L]] =
    new AppendOne[Tuple3[T1, T2, T3], L] {
      type Out = Tuple4[T1, T2, T3, L]

      def apply(prefix: Tuple3[T1, T2, T3], last: L): Tuple4[T1, T2, T3, L] = Tuple4(prefix._1, prefix._2, prefix._3, last)
    }
}