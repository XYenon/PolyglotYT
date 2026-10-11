# Third-party source attribution

The translation prompt presets in `KissPrompts.kt` and the Kotlin adaptations of subtitle preprocessing and segmentation are based on [fishjar/kiss-translator](https://github.com/fishjar/kiss-translator), revision `df3e1e3fe1ada6f81e0081e4502eb2faecbd602d`.

Original files: `src/config/api.js`, `src/apis/trans.js`, `src/subtitle/youtubeSubtitleProcessing.js`, `src/subtitle/sentenceBreaker.js`, `src/subtitle/subtitleBoundaryProtocol.js`, and `src/subtitle/subtitleTextClassification.js`.

Copyright belongs to the kiss-translator contributors. Their work is licensed under the GNU General Public License version 3, as is this project; the license text is in `LICENSE`. The adaptations change JavaScript APIs to Kotlin, use Android's native subtitle rendering, and omit unrelated browser features.
