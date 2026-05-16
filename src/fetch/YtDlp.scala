package kidstv.fetch

import cats.effect.IO
import io.circe.Json
import io.circe.parser.parse

case class YtDlpResult(
  title: String,
  durationSeconds: Option[Int],
  rawJson: Json
)

object YtDlp:

  def download(url: String, outputTemplate: String): IO[Unit] =
    IO.blocking:
      val result = os.proc("yt-dlp", "-o", outputTemplate, url).call(
        mergeErrIntoOut = false,
        check = false
      )
      if result.exitCode != 0 then
        throw RuntimeException(s"yt-dlp download failed (exit ${result.exitCode}): ${result.err.text()}")

  def fetchMetadata(url: String): IO[YtDlpResult] =
    IO.blocking:
      val result = os.proc("yt-dlp", "--dump-json", "--no-download", url).call(
        mergeErrIntoOut = false,
        check = false
      )
      if result.exitCode != 0 then
        throw RuntimeException(s"yt-dlp failed (exit ${result.exitCode}): ${result.err.text()}")
      val json = parse(result.out.text()).getOrElse:
        throw RuntimeException("yt-dlp returned invalid JSON")
      val cursor = json.hcursor
      YtDlpResult(
        title = cursor.get[String]("title").getOrElse("Unknown"),
        durationSeconds = cursor.get[Double]("duration").toOption.map(_.toInt),
        rawJson = json
      )
