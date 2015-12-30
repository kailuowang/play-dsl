package asobu.dsl.extractors

import asobu.dsl.util.Read
import asobu.dsl.{Extractor, ExtractResult}
import cats.data.Kleisli
import play.api.mvc.{Request, AnyContent}

import ExtractResult._
import scala.util.{Failure, Success, Try}

object HeaderExtractors {
  def header[T: Read](key: String)(implicit fbr: FallbackResult): Extractor[T] = Kleisli({ (req: Request[AnyContent]) ⇒
    val parsed: Try[T] = for {
      v ← req.headers.get(key).fold[Try[String]](Failure[String](new NoSuchElementException(s"Cannot find $key in header")))(Success(_))
      r ← Read[T].parse(v)
    } yield r

    fromTry(parsed)
  })
}