package icu.nullptr.polyglot.translate

/** Prompts adapted from fishjar/kiss-translator df3e1e3 (GPL-3.0). See THIRD_PARTY.md. */
object KissPrompts {

    val SINGLE_LEGACY_SYSTEM = """You are a professional, authentic machine translation engine."""

    val SINGLE_LEGACY_USER = """# Context
Title: {{title}}
Description: {{description}}
Summary: {{summary}}
Tone: {{tone}}

# Glossary:
{{glossary}}

# Task
Translate the Source Text below to {{to}}.
1. Use the Context to ensure accuracy.
2. Adapt the wording to match the specified Tone.
3. If a Source Text term exactly matches a Glossary entry, output ONLY that entry's value; never repeat the source term, never wrap the value in parentheses.
4. Output ONLY the translated text. No markdown, no explanations.

Source Text: {{text}}

Translated Text:"""

    val SINGLE_CONCISE_SYSTEM = """"""

    val SINGLE_CONCISE_USER = """{{glossary}}
{{tone}}
Translate the following text into {{to}}. Preserve all HTML-like tags. Output ONLY the translated text without any explanation:
{{text}}"""

    val BATCH_JSON_SYSTEM = """Act as a professional machine translation engine. Output a raw JSON array strictly. No Markdown fences, no conversational filler."""

    val BATCH_JSON_USER = """{{title}}
{{description}}
{{summary}}
{{context}}
{{glossary}}
{{tone}}
Translate the text field of each object in the JSON array below into {{to}}. Keep the id unchanged and output a raw JSON array. Output ONLY the translated JSON result without any additional explanation:
{{segments}}"""

    val BATCH_XML_SYSTEM = """Act as a professional machine translation engine. Output raw XML format only (<root><t id="0">...</t></root>). No Markdown fences, no conversational filler."""

    val BATCH_XML_USER = """{{title}}
{{description}}
{{summary}}
{{context}}
{{glossary}}
{{tone}}
Translate the text within each <t id="..."> tag below into {{to}}. Maintain the exact <t id="..."> tag structure and id attributes. Preserve all internal HTML-like tags. Output ONLY the translated XML result without any additional explanation:
{{segments}}"""

    val BATCH_LINE_SYSTEM = """Act as a professional machine translation engine. Output raw text lines in "ID | Text" format strictly. No Markdown fences, no conversational filler."""

    val BATCH_LINE_USER = """{{glossary}}
{{tone}}
Translate each numbered line below into {{to}}. Maintain the exact "{id} | {translation}" format for each line. Preserve all HTML-like tags, and keep <br> for internal newlines. Output ONLY the translated result without any additional explanation:
{{segments}}"""

    val SUBTITLE_SYSTEM = """# Context
Title: {{title}}
Description: {{description}}
Summary: {{summary}}
Tone: {{tone}}

# Glossary (Terminology):
{{glossary}}

# Task
Group the input word-level JSON array into readable, well-paced bilingual subtitle segments. Target Language: {{to}}.

# Output Contract
1. STRICTLY output a valid JSON array only. No markdown formatting (e.g., do not use ```json fences), no preamble, and no postscript.
2. Format per element: {"e":<last_word_id>, "o":"exact merged source text", "t":"translation"}
3. The "e" field must be an inclusive, exact word ID from the input and must increase strictly. The first segment starts at ID 0; every later segment starts at the previous "e" + 1.
4. Completeness: Cover every input item exactly once. The final "e" must equal the final input ID. No missing items, overlaps, or gaps.
5. For each segment, first determine its exact input range from the previous "e" + 1 through the current "e", then merge every source item in that range verbatim into "o". Do not paraphrase, normalize, translate, omit, or add source text in "o".
6. Do not return start IDs, timestamps, or any extra fields. The application reconstructs them from the input.

# Rules
1. Hard Source Length Limit:
   - For space-separated source languages, each source span MUST contain no more than 15 words; aim for 8-12 words when a natural boundary exists.
   - For Chinese, Japanese, and other non-space-separated source languages, each source span MUST contain no more than 30 source characters.
   - If a sentence exceeds the applicable limit, split it at a clause, comma, conjunction, or natural phrase boundary. The hard length limit takes priority over keeping a long grammatical sentence intact.
2. Sentence Boundaries: Never merge two complete sentences into one subtitle segment. Terminal punctuation such as .?!。！？ normally ends the current segment.
3. Pause Indicators: An optional "pauseMs" field is the timeline gap in milliseconds after the current input item. If "pauseMs" is missing, treat it as 0 milliseconds and do not infer a pause. Larger positive values indicate stronger sentence boundaries, but grammatical correctness and semantic coherence always take priority.
4. Exact Translation Alignment: Build "o" first from the exact source span covered by the current "e", starting after the previous "e". Then translate only the current "o" into "t". The "t" field MUST NOT omit that span, translate future input items, or carry text from adjacent segments.
5. Silent Self-Check: Before returning, silently verify that every "e", "o", and "t" correspond one-to-one, every "o" matches its exact input range, all source-length limits are satisfied, and all input items are covered exactly once. Do not output the self-check or any reasoning.
6. Translation Quality: Keep "t" concise, accurate, and natural while strictly adhering to the provided Context, Tone, and Glossary. When a term matches the Glossary, output ONLY the glossary value; never echo the source term alongside it and never wrap the value in parentheses.

# Example
Input: [{"id":0,"text":"Once"},{"id":1,"text":"the"},{"id":2,"text":"assets"},{"id":3,"text":"are"},{"id":4,"text":"ready,"},{"id":5,"text":"open"},{"id":6,"text":"the"},{"id":7,"text":"storyboard"},{"id":8,"text":"tab.","pauseMs":850},{"id":9,"text":"This"},{"id":10,"text":"is"},{"id":11,"text":"where"},{"id":12,"text":"everything"},{"id":13,"text":"comes"},{"id":14,"text":"together."},{"id":15,"text":"If"},{"id":16,"text":"a"},{"id":17,"text":"scene"},{"id":18,"text":"does"},{"id":19,"text":"not"},{"id":20,"text":"match"},{"id":21,"text":"your"},{"id":22,"text":"idea,"},{"id":23,"text":"regenerate"},{"id":24,"text":"it"},{"id":25,"text":"or"},{"id":26,"text":"adjust"},{"id":27,"text":"the"},{"id":28,"text":"prompt"},{"id":29,"text":"carefully"},{"id":30,"text":"until"},{"id":31,"text":"it"},{"id":32,"text":"feels"},{"id":33,"text":"right."}]
Output: [{"e":8,"o":"Once the assets are ready, open the storyboard tab.","t":"素材准备好后，打开故事板标签页。"},{"e":14,"o":"This is where everything comes together.","t":"一切从这里开始整合。"},{"e":22,"o":"If a scene does not match your idea,","t":"如果某个场景与你的想法不符，"},{"e":33,"o":"regenerate it or adjust the prompt carefully until it feels right.","t":"请重新生成，或仔细调整提示词，直到效果合适。"}]"""

    val SUMMARY_SYSTEM = """Analyze the video title, description, and transcript below. Produce a concise briefing (max 300 words) to help a subtitle translator understand the content accurately.

Cover these aspects:
1. Main topic, themes, and subject domain
2. Key terminology with brief definitions or context
3. Important proper nouns (people, organizations, products, places)
4. Speaker's tone and register
5. Abbreviations, jargon, or ambiguous terms needing consistent handling

Output plain text only. No markdown, no formatting, no headers."""

}
