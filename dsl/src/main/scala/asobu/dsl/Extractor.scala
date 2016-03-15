package asobu.dsl

import cats.data.{Kleisli, Xor, XorT}
import play.api.mvc.Results._
import play.api.mvc.{AnyContent, Request, Result}
import shapeless.ops.hlist._
import shapeless._

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}
import ExtractResult._
import cats.syntax.all._
import CatsInstances._
import concurrent.ExecutionContext.Implicits.global
import cats.sequence._

object Extractor extends ExtractorBuilderSyntax {
  def empty[TFrom]: Extractor[TFrom, HNil] = apply(_ ⇒ HNil)

  def apply[TFrom, T](f: TFrom ⇒ T): Extractor[TFrom, T] = f map pure

  implicit def fromFunction[TFrom, T](f: TFrom ⇒ ExtractResult[T]): Extractor[TFrom, T] = Kleisli(f)

  implicit def fromFunctionXorT[TFrom, T](f: TFrom ⇒ XorTF[T]): Extractor[TFrom, T] = f.andThen(ExtractResult(_))

}

object RequestExtractor {
  val empty = Extractor.empty[Request[AnyContent]]
  def apply[T](f: Request[AnyContent] ⇒ T): RequestExtractor[T] = Extractor(f)
}

trait ExtractorBuilderSyntax {

  /**
   * extractor from a list of functions
   * e.g. from(a = (_:Request[AnyContent]).headers("aKay"))
   */
  object from extends shapeless.RecordArgs {
    def applyRecord[TFrom, Repr <: HList, Out <: HList](repr: Repr)(
      implicit
      seq: RecordSequencer.Aux[Repr, TFrom ⇒ Out]
    ): Extractor[TFrom, Out] = {
      Extractor(seq(repr))
    }
  }

  /**
   * extractor composed of several extractors
   * e.g. compose(a = RequestExtractor(_.headers("aKey"))
   * or
   * compose(a = header("aKey"))  //header is method that constructor a more robust Extractor
   * This function needs an implicit ExecutionContext in scope otherwise it will complain that
   * RecordSequencer can't be find, because Functor of Future can't be found.
   */
  def compose = sequenceRecord

  /**
   * combine two extractors into one that takes two inputs as a tuple and returns a concated list of the two results
   * @return
   */
  def zip[FromA, FromB, LA <: HList, LB <: HList, LOut <: HList](
    ea: Extractor[FromA, LA],
    eb: Extractor[FromB, LB]
  )(
    implicit
    prepend: Prepend.Aux[LA, LB, LOut]
  ): Extractor[(FromA, FromB), LOut] = Extractor.fromFunction { (p: (FromA, FromB)) ⇒
    val (a, b) = p
    for {
      ra ← ea.run(a)
      rb ← eb.run(b)
    } yield ra ++ rb
  }

  def combine[TFrom, L1 <: HList, L2 <: HList, Out <: HList](self: Extractor[TFrom, L1], that: Extractor[TFrom, L2])(
    implicit
    prepend: Prepend.Aux[L1, L2, Out]
  ): Extractor[TFrom, Out] = {
    (self |@| that) map (_ ++ _)
  }
}

trait DefaultExtractorImplicits {
  implicit val ifFailure: FallbackResult = e ⇒ BadRequest(e.getMessage)
}

object DefaultExtractorImplicits extends DefaultExtractorImplicits

trait ExtractorOps {
  import Extractor._

  implicit class extractorOps[TFrom, Repr <: HList](self: Extractor[TFrom, Repr]) {
    def and[ThatR <: HList, ResultR <: HList](that: Extractor[TFrom, ThatR])(
      implicit
      prepend: Prepend.Aux[Repr, ThatR, ResultR]
    ): Extractor[TFrom, ResultR] = combine(self, that)
  }

}

