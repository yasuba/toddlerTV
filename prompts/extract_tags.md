You are analysing metadata for a children's video to extract structured tags.

Given the metadata below, return a JSON object with exactly these fields:

- `pacing`: one of "Calm", "Moderate", "Stimulating", "Hyperactive"
- `contentType`: one of "Narrative", "Musical", "Educational", "Mixed"
- `moods`: an array of one or more of "WindDown", "Energetic", "Curious", "Silly", "Cosy"
- `ageRange`: an object with `minMonths` and `maxMonths` (integers)
- `hasMusic`: boolean
- `hasNarration`: boolean

Use the video title, description, channel, duration, and any other available metadata to make your best judgement. For age range, be conservative — if unsure, use a wider range.

Return ONLY the JSON object, no surrounding text.
