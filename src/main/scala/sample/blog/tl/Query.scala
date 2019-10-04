package sample.blog
package tl

case class Query[I](input: QuerySegment[I]) {

  def +[A, IA](in: QuerySegment[A])(implicit ts: ParamConcat.Aux[I, A, IA]): Query[IA] =
    copy[IA](input + in)

  override def toString: String = input.toString
}
