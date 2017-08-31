package sample.blog.configv

import shapeless._
import scala.concurrent.duration.FiniteDuration

object ConfigValidation {

  case object ShouldBeAPercentageValue extends Exception

  case class HttpConfig(host: String, port: Int)
  case class MySettings(name: String, heartbeat: FiniteDuration, http: HttpConfig)

  //http://www.cakesolutions.net/teamblogs/using-shapeless-to-validate-typesafe-configuration-data

  // Following allows Shapeless to create instances of our sealed abstract case classes
  implicit val genHttpConfig: Generic[HttpConfig] =
    new Generic[HttpConfig] {
      override type Repr = String :: Int :: HNil
      override def to(t: HttpConfig): Repr = t.host :: t.port :: HNil
      override def from(r: Repr): HttpConfig = new HttpConfig(r(0), r(1))
    }

  implicit val genSettings: Generic[MySettings] =
    new Generic[MySettings] {
      override type Repr = String :: FiniteDuration :: HttpConfig :: HNil
      override def to(t: MySettings): Repr = t.name :: t.heartbeat :: t.http :: HNil
      override def from(r: Repr): MySettings = new MySettings(r(0), r(1), r(2))
    }

  def toHList[P <: Product, L <: HList](p: P)(implicit gen: Generic.Aux[P, L]): L = {
    gen.to(p)
  }

  import scala.concurrent.duration._
  //val settings = genSettings.to(MySettings("orders", 3.seconds, HttpConfig("192.168.0.1", 9042)))
  //genSettings.from(settings)

  val settings = toHList(MySettings("orders", 3.seconds, HttpConfig("192.168.0.1", 9042)))


  //create("192.168.0.1", 9042)

  /*
  import cats.data.Validated
  import cats.syntax.cartesian._
  import scala.concurrent.duration._
  import scala.util.Try

  case object NameShouldBeNonEmptyAndLowerCase extends Exception
  case object ShouldBePositive extends Exception

  sealed abstract case class HttpConfig(host: String, port: Int)
  sealed abstract case class Settings(name: String, timeout: FiniteDuration, http: HttpConfig)

  validateConfig[Settings]("application.conf") { implicit config =>
    (validate[String]("name", NameShouldBeNonEmptyAndLowerCase)(_.matches("[a-z0-9_-]+")) |@|
      validate[FiniteDuration]("http.timeout", ShouldBePositive)(_ >= 0.seconds) |@|
      via[HttpConfig]("http") { implicit config =>
        (unchecked[String]("host") |@| validate[Int]("port", ShouldBePositive)(_ > 0)).map(HttpConfig(_, _))
      }
    ).map(Settings(_, _, _))
  }*/
}
