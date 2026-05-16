package kidstv.cli

import kidstv.domain.*

object Output:

  def formatDuration(seconds: Int): String =
    val m = seconds / 60
    val s = seconds % 60
    f"$m%d:$s%02d"

  def statusLabel(s: VideoStatus): String = s match
    case _: VideoStatus.Candidate => "Candidate"
    case _: VideoStatus.Reviewing => "Reviewing"
    case _: VideoStatus.Approved  => "Approved"
    case _: VideoStatus.Rejected  => "Rejected"
    case _: VideoStatus.Retired   => "Retired"

  def printVideo(v: Video): String =
    val dur = v.runtimeSeconds.map(formatDuration).getOrElse("??:??")
    val tags = v.tags.map(t => s"\n  pacing=${t.pacing} type=${t.contentType} moods=${t.moods.mkString(",")}").getOrElse("")
    s"""  ${v.id}
       |  ${v.title}  [$dur]
       |  ${v.sourceUrl}
       |  status: ${statusLabel(v.status)}$tags""".stripMargin

  def table(videos: Vector[Video]): String =
    if videos.isEmpty then return "No videos found."

    val headers = Vector("ID", "TITLE", "DURATION", "STATUS", "PACING")
    val rows = videos.map: v =>
      Vector(
        v.id,
        truncate(v.title, 40),
        v.runtimeSeconds.map(formatDuration).getOrElse("-"),
        statusLabel(v.status),
        v.tags.map(_.pacing.toString).getOrElse("-")
      )

    val allRows = headers +: rows
    val widths = headers.indices.map: col =>
      allRows.map(_(col).length).max

    def formatRow(row: Vector[String]): String =
      row.zip(widths).map((cell, w) => cell.padTo(w, ' ')).mkString("  ")

    val header = formatRow(headers)
    val separator = widths.map("-" * _).mkString("  ")
    val body = rows.map(formatRow)
    (header +: separator +: body).mkString("\n")

  private def truncate(s: String, max: Int): String =
    if s.length <= max then s else s.take(max - 3) + "..."
