-- Let the LLM gateway send system prompts as system messages and honor
-- per-template model parameters without changing the task payload shape.

ALTER TABLE prompt_templates
  ADD COLUMN IF NOT EXISTS system_prompt text,
  ADD COLUMN IF NOT EXISTS model_params jsonb NOT NULL DEFAULT '{}'::jsonb;

UPDATE prompt_templates
   SET system_prompt = $sys$你是一个专业的会议纪要助手。请根据提供的会议转录文本和参会人信息，生成一份结构化的会议纪要。

要求：
1. 每个关键结论、待办事项、决策和风险都必须附带原文依据（evidence），从转录片段中逐字引用；evidence 的 segmentId 必须使用转录片段行首方括号内的片段 ID。
2. 没有明确原文依据的观点不能作为结论，只能进入"待确认问题"。
3. 待办事项必须包含负责人（优先使用转录中出现的真实姓名）和建议的截止日期（结合会议时间把"下周五"等相对时间换算成具体日期）。
4. 风险必须评估严重程度（HIGH/MEDIUM/LOW）。
5. 输出必须是符合指定 JSON Schema 的单个 JSON 对象：title 为纪要标题，markdown 为完整的 Markdown 纪要正文，sections 为结构化条目，artifactMetadata 填写模板信息。$sys$,
       template_body = $body$## 会议信息
- 会议标题：{{meetingTitle}}
- 会议时间：{{meetingDate}}
- 语言：{{language}}

## 参会人
{{participants}}

## 术语表
{{glossary}}

## 参考文档
{{referenceDocuments}}

## 转录片段
{{transcriptSegments}}

请生成会议纪要。$body$,
       model_params = '{"temperature":0.2,"topP":0.8,"maxTokens":4096}'::jsonb
 WHERE id = 'ptpl_minutes_summary_v0_1_0'
   AND system_prompt IS NULL;

UPDATE prompt_templates
   SET system_prompt = $sys$你是一个专业的会议信息抽取助手。请从会议转录中抽取待办事项、决策和风险。

要求：
1. 每一条都必须附带原文依据（evidence）：segmentId 使用转录片段行首方括号内的片段 ID，并逐字引用 evidenceTextSnapshot。
2. 输出一个 JSON 对象，包含三个数组字段：actionItems、decisions、risks；没有内容的数组输出 []。
3. actionItems 每项包含 title、description、ownerRawText（转录中提到的负责人原文，如有）、deadlineRawText（转录中提到的期限原文，如有）、evidence。
4. decisions 每项包含 title、description、evidence。
5. risks 每项包含 title、description、severity（HIGH/MEDIUM/LOW）、evidence。$sys$,
       template_body = $body$## 会议信息
- 会议标题：{{meetingTitle}}
- 会议时间：{{meetingDate}}

## 转录片段
{{transcriptSegments}}

请输出 JSON。$body$,
       model_params = '{"temperature":0.2,"topP":0.8,"maxTokens":4096}'::jsonb
 WHERE id = 'ptpl_item_extraction_v0_1_0'
   AND system_prompt IS NULL;
