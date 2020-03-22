package sample.blog

package object tl {

  sealed trait QuerySegment[T] {
    def +[A, TA](other: QuerySegment[A])(implicit ts: ParamConcat.Aux[T, A, TA]): QuerySegment[TA]
    //def ++[A](other: QuerySegment[A]): QuerySegment[T with A]
  }

  object QuerySegment {

    sealed trait One[T] extends QuerySegment[T] {
      def +[A, TA](other: QuerySegment[A])(implicit ts: ParamConcat.Aux[T, A, TA]): QuerySegment[TA] =
        other match {
          case s: One[_]    ⇒ Many(Vector(this, s))
          case Many(inputs) ⇒ Many(this +: inputs)
        }
    }

    case class Many[T](inputs: Vector[One[_]]) extends QuerySegment[T] {
      override def +[A, TA](other: QuerySegment[A])(implicit ts: ParamConcat.Aux[T, A, TA]): QuerySegment.Many[TA] =
        other match {
          case s: One[_] ⇒ Many(inputs :+ s)
          case Many(m)   ⇒ Many(inputs ++ m)
        }
      override def toString: String = if (inputs.isEmpty) "-" else inputs.map(_.toString).mkString(",")
    }

    final case class Empty(name: String) extends One[Unit] {
      override val toString: String = name
    }

    final case class Value[T: scala.reflect.ClassTag](in: T) extends One[T] {
      override val toString: String =
        s" ${implicitly[scala.reflect.ClassTag[T]].runtimeClass.getSimpleName}:$in"
    }
  }

  trait ParamConcat[T, U] {
    type Out
  }

  object ParamConcat extends LowPriorityTupleConcat3 {
    implicit def concatUnitLeft[U]: Aux[Unit, U, U] = null
    implicit def concatNothingLeft[U]: Aux[Nothing, U, U] = null // for void outputs
  }

  trait LowPriorityTupleConcat3 extends LowPriorityTupleConcat2 {
    implicit def concatUnitRight[U]: Aux[U, Unit, U] = null
    implicit def concatNothingRight[U]: Aux[U, Nothing, U] = null // for void outputs
  }

  trait LowPriorityTupleConcat2 extends LowPriorityTupleConcat1 {
    implicit def concatTuples[T, U, TU](implicit tc: TupleOps.JoinAux[T, U, TU]): Aux[T, U, TU] = null
  }

  trait LowPriorityTupleConcat1 extends LowPriorityTupleConcat0 {
    implicit def concatSingleAndTuple[T, U, TU](implicit tc: TupleOps.JoinAux[Tuple1[T], U, TU]): Aux[T, U, TU] = null
    implicit def concatTupleAndSingle[T, U, TU](implicit tc: TupleOps.JoinAux[T, Tuple1[U], TU]): Aux[T, U, TU] = null
  }

  trait LowPriorityTupleConcat0 {
    type Aux[T, U, TU] = ParamConcat[T, U] { type Out = TU }

    implicit def concatSingleAndSingle[T, U, TU](implicit tc: TupleOps.JoinAux[Tuple1[T], Tuple1[U], TU]): Aux[T, U, TU] = null
  }

}
