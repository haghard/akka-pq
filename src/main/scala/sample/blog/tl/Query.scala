package sample.blog
package tl

case class Query[I](input: QuerySegment[I]) {

  //creates a product
  def +[A, IA](in: QuerySegment[A])(implicit ts: ParamConcat.Aux[I, A, IA]): Query[IA] =
    copy[IA](input + in)

  override def toString: String = input.toString
}
