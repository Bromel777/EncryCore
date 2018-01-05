package encry.api.routes

import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.http.scaladsl.server.Route
import scorex.core.api.http.{ApiError, ApiRoute, ScorexApiResponse}

import scala.concurrent.{ExecutionContextExecutor, Future}

trait EncryBaseApiRoute extends ApiRoute {

  implicit val ec: ExecutionContextExecutor = context.dispatcher

  protected def toJsonResponse(fn: ScorexApiResponse): Route = {
    val resp = complete(HttpEntity(ContentTypes.`application/json`, fn.toJson.spaces2))
    withCors(resp)
  }

  protected def toJsonResponse(fn: Future[ScorexApiResponse]): Route = onSuccess(fn) { toJsonResponse }

  protected def toJsonOptionalResponse(fn: Future[Option[ScorexApiResponse]]): Route = {
    onSuccess(fn) {
      case Some(v) => toJsonResponse(v)
      case None => toJsonResponse(ApiError("not-found",404))
    }
  }

}