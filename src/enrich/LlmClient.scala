package kidstv.enrich

import cats.effect.IO
import io.circe.*
import io.circe.syntax.*
import sttp.client3.*

trait LlmClient:
  def complete(systemPrompt: String, userMessage: String): IO[String]

object AnthropicClient:

  def make(apiKey: String): LlmClient = new LlmClient:
    private val backend = HttpClientSyncBackend()
    private val baseUrl = uri"https://api.anthropic.com/v1/messages"

    def complete(systemPrompt: String, userMessage: String): IO[String] =
      IO.blocking:
        val body = Json.obj(
          "model"      -> "claude-haiku-4-5-20251001".asJson,
          "max_tokens" -> 1024.asJson,
          "system"     -> Json.arr(
            Json.obj(
              "type" -> "text".asJson,
              "text" -> systemPrompt.asJson,
              "cache_control" -> Json.obj("type" -> "ephemeral".asJson)
            )
          ).asJson,
          "messages"   -> Json.arr(
            Json.obj("role" -> "user".asJson, "content" -> userMessage.asJson)
          )
        )
        val request = basicRequest
          .post(baseUrl)
          .header("x-api-key", apiKey)
          .header("anthropic-version", "2023-06-01")
          .header("anthropic-beta", "prompt-caching-2024-07-31")
          .contentType("application/json")
          .body(body.noSpaces)

        val response = request.send(backend)
        response.body match
          case Right(responseBody) =>
            val json = io.circe.parser.parse(responseBody).getOrElse:
              throw RuntimeException(s"Anthropic returned invalid JSON: ${responseBody.take(200)}")
            json.hcursor
              .downField("content").downArray.downField("text").as[String]
              .getOrElse:
                throw RuntimeException(s"Unexpected Anthropic response structure: ${responseBody.take(500)}")
          case Left(errorBody) =>
            throw RuntimeException(s"Anthropic API error: $errorBody")
