# Subtitle processing

The core processing defaults and prompt presets follow [kiss-translator](https://github.com/fishjar/kiss-translator) revision `df3e1e3fe1ada6f81e0081e4502eb2faecbd602d`. Attribution is in [THIRD_PARTY.md](../THIRD_PARTY.md).

## Defaults

| Setting | Default |
| --- | --- |
| Service | Microsoft, retaining an existing saved selection |
| Target language | Simplified Chinese |
| Display | Original above translation |
| Auto translation | On |
| Built-in segmentation | Rule |
| AI segmentation / video summary | Off |
| Force retranslation of AI subtitles | Off |
| AI input chunk / long sentence threshold | 1,000 / 60 characters |
| Pretranslation / throttle | 90 / 30 seconds |
| Request timeout | 30 seconds |
| Request concurrency / start interval | 10 / 100 milliseconds |
| Retry count / retry interval | 2 / 1,000 milliseconds |
| Batch collection delay | 400 milliseconds |
| Texts / characters per batch | 20 / 10,000 |
| Concurrent batches | 10; history-enabled requests serialize |
| OpenAI batch / streaming | On / on |
| Stream display | Realtime |
| Conversation history / message limit | Off / 3, rounded down to complete user/assistant pairs |
| Tone / terminology | `formal` / empty |
| Language variant translation | On |
| Single / batch preset | Concise / JSON |

The long-sentence threshold defaults to 60 rather than kiss's 100, to request earlier secondary rule segmentation. This is a trigger, not a hard per-line length limit; statistical/AI segmentation has its own limits, and saved thresholds retain their values. The `formal` tone and all prompt presets remain the reference defaults.

The existing OpenAI model and its `gpt-4o-mini` fallback stay unchanged. Requests omit `temperature`, `max_tokens`, and `max_completion_tokens`. Saved service, language, timeout, credentials, and model settings retain their values.

## Pipeline

- JSON3 and srv3 decoder input preserves word offsets, append/newline markers, and ASR confidence presence. Binary/native input falls back to recovering increments from the native cumulative timeline.
- Preprocessing, rule segmentation, statistical segmentation, and AI input chunking are Kotlin adaptations of the pinned reference.
- Original and translated captions both use the selected kiss rule/statistical/AI segments. Native timeline intervals use their complete source text instead of YouTube's original hard line breaks or progressively revealed fragments; word boundaries are retained as additional native events so later AI boundaries can be displayed.
- Every native window update selects the current processed cue by playback time and renders that same complete source in its current/full text fields. Changes to either source or translation replay the native window, including clearing text in cue gaps. Model fields are restored after the update. YouTube determines wrapping and layout; the module does not merge renderer rows or apply bilingual row transforms.
- Pretranslation selects captions in the current playback window, not a fixed number of captions. A monotonic throttle and immediate seek detection govern requests.
- OpenAI supports JSON, XML, and LINE batch prompt presets, plus custom versions of each. Single-text presets include concise, legacy context-rich, and custom. AI segmentation and video-summary prompts are editable independently.
- Completed streaming translations are cached immediately. Drafts are displayable but do not count as final translations. Missing streaming results trigger a non-stream request; invalid or duplicate IDs never become positional translations.
- AI segmentation uses the boundary `{e,o,t}` JSON protocol. Source text and timestamps come from the original events. Only a continuous prefix is accepted; one tail retry precedes rule fallback and full coverage validation.
- AI segmentation and summary use the existing OpenAI credentials and model, even when the selected ordinary translator is Microsoft or Google. A valid OpenAI configuration is needed to enable these optional features.
- Video context is obtained from the caption track's video URL and watch-page player response. Patched hosts with a VideoInformation bridge also supply video ID/title. Metadata lookup failure leaves these fields empty while captions continue processing.
- Persistent disk cache stores translations, AI chunks, summaries, and video metadata. Request settings are hashed; authorization keys are not stored in cache files.
- Track/video/settings changes cancel queued and active network work. Responses additionally check the active caption generation before updating the view.

The displayed original is kiss's complete processed sentence, rather than YouTube's original multiline or progressively revealed text. Source and translation switch together on processed cue boundaries. Native subtitle controls still render both texts and may wrap long sentences onto multiple lines; segmentation does not force a single visual line. Original style spans are not carried into reconstructed source text. Font size remains native and the existing one-third bottom-gap reduction remains in effect. A host playback bridge supplies continuous timing where available; other hosts rely on native caption event positions, so asynchronous updates during a paused frame or a gap can differ.

The processing settings appear in YouTube's PolyglotYT settings under Subtitle processing, Translation requests, and Translation prompts. Custom templates use kiss placeholders, including `{{text}}`, `{{segments}}`, `{{to}}`, `{{title}}`, `{{description}}`, `{{summary}}`, `{{glossary}}`, and `{{tone}}`. Terminology uses `source,translation` entries separated by newlines or semicolons.

## Validation

Run `./gradlew :app:assembleDebug :app:testDebugUnitTest --console=plain`.

`app/src/test/resources/kiss-subtitles.json.gz` contains 150 reproducible cases generated by the pinned reference. Tests compare preprocessing, both built-in segmenters, and chunking, and cover response IDs, incomplete streams, fallback, playback windows, seek/pause behavior, cancellation, native increments, srv3 offsets, AI coverage, cache persistence, and request body constraints. Presentation tests also cover complete-source replacement of native fragments, caption gaps, fractional timestamps, and changing AI boundaries. Formatter tests cover untouched fallback text and all three bilingual display orders.

The 48 cases in `kiss-prompts.json` compare fully rendered single, batch, and AI segmentation prompts against the reference, including empty metadata, glossary entries, and tone. Regenerate them with `node scripts/generate-kiss-prompt-fixtures.cjs /path/to/kiss-translator`.

To regenerate the subtitle fixtures, check out the pinned reference and run `node scripts/generate-kiss-fixtures.cjs /path/to/kiss-translator` from this repository root. Host integration still needs device playback testing: passing JVM tests does not establish that every obfuscated YouTube build uses the same decoder and playback event shapes.
