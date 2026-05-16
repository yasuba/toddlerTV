package kidstv.storage

import com.augustnagro.magnum.*
import io.circe.syntax.*
import io.circe.parser.decode
import java.time.Instant
import kidstv.domain.*
import kidstv.domain.Codecs.given

object HistoryRepo:

  def insertSession(session: Session)(using DbCon): Unit =
    val builtAt = session.builtAt.toString
    val moodFilter = session.moodFilter.map(_.toString)
    val videoIds = session.videoIds.asJson.noSpaces
    sql"""INSERT INTO sessions (id, built_at, target_duration_seconds, mood_filter, video_ids)
          VALUES (${session.id}, $builtAt, ${session.targetDurationSeconds}, $moodFilter, $videoIds)""".update.run()

  def recentSessionVideoIds(lastN: Int)(using DbCon): Set[String] =
    val rows = sql"SELECT video_ids FROM sessions ORDER BY built_at DESC LIMIT $lastN"
      .query[String]
      .run()
    rows.flatMap: json =>
      decode[List[String]](json).getOrElse(Nil)
    .toSet
