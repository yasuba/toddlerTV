package kidstv.storage

import com.augustnagro.magnum.*

object TagOverrideRepo:

  def findByVideoId(videoId: String)(using DbCon): Vector[(String, String)] =
    sql"SELECT field_name, value FROM tag_overrides WHERE video_id = $videoId"
      .query[(String, String)]
      .run()
