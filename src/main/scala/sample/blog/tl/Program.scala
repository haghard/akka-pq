package sample.blog
package tl

import sample.blog.tl.QuerySegment._

object Program {

  //Scala World 2019:Adam Warski: Designing Programmer-Friendly APIs: https://www.youtube.com/watch?v=I3loMuHnYqw
  val q: Query[(Int, Double, String)] = Query(Value(1)) + Value(2.1) + Value("bla")
}
