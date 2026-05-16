package kidstv.storage

import com.augustnagro.magnum.*
import io.circe.Json
import java.time.Instant

object MetadataRepo:

  def insert(videoId: String, source: String, rawJson: Json)(using DbCon): Unit =
    val fetchedAt = Instant.now().toString
    val jsonStr = rawJson.noSpaces
    sql"""INSERT INTO external_metadata (video_id, source, fetched_at, raw_json)
          VALUES ($videoId, $source, $fetchedAt, $jsonStr)
          ON CONFLICT (video_id, source) DO UPDATE SET fetched_at = $fetchedAt, raw_json = $jsonStr""".update.run()

  def findByVideoId(videoId: String)(using DbCon): Vector[(String, Json)] =
    sql"SELECT source, raw_json FROM external_metadata WHERE video_id = $videoId"
      .query[(String, String)]
      .run()
      .map: (source, jsonStr) =>
        val json = io.circe.parser.parse(jsonStr).getOrElse(Json.Null)
        (source, json)
