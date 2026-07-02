-- Seed global (tenant_id NULL) prompt templates for the two Java LLM phases.
--
-- Until now prompt_templates had NO seed and no loader for the resource files
-- under classpath:prompts/** — a fresh deployment failed SUMMARY/EXTRACTION
-- with "active prompt template not found", or ran whatever was inserted by
-- hand. These bodies are the runtime source of truth; their placeholders are
-- kept in lockstep with the variables MinutesApplicationService /
-- ExtractionApplicationService provide (the gateway now logs
-- llm_template_unresolved_variables when they drift).
--
-- Guarded by NOT EXISTS on task_name so an operator-managed template is never
-- overridden; the whole template body is a single user message (the gateway
-- does not send a separate system message).

INSERT INTO prompt_templates (
  id, tenant_id, task_name, version, major_version, minor_version, patch_version,
  template_body, json_schema, status
)
SELECT
  'ptpl_minutes_summary_v0_1_0', NULL, 'MINUTES_SUMMARY', '0.1.0', 0, 1, 0,
  $body$你是一个专业的会议纪要助手。请根据提供的会议转录文本和参会人信息，生成一份结构化的会议纪要。

要求：
1. 每个关键结论、待办事项、决策和风险都必须附带原文依据（evidence），从转录片段中逐字引用；evidence 的 segmentId 必须使用转录片段行首方括号内的片段 ID。
2. 没有明确原文依据的观点不能作为结论，只能进入"待确认问题"。
3. 待办事项必须包含负责人（优先使用转录中出现的真实姓名）和建议的截止日期（结合会议时间把"下周五"等相对时间换算成具体日期）。
4. 风险必须评估严重程度（HIGH/MEDIUM/LOW）。
5. 输出必须是符合指定 JSON Schema 的单个 JSON 对象：title 为纪要标题，markdown 为完整的 Markdown 纪要正文，sections 为结构化条目，artifactMetadata 填写模板信息。

## 会议信息
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
  $schema${
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "meeting_minutes_v1",
  "type": "object",
  "required": ["sections", "artifactMetadata"],
  "properties": {
    "title": { "type": "string" },
    "markdown": { "type": "string" },
    "sections": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["type", "title"],
        "properties": {
          "type": { "type": "string", "enum": ["CONCLUSION", "DISCUSSION", "DECISION", "ACTION_ITEM", "RISK", "QUESTION"] },
          "title": { "type": "string" },
          "items": {
            "type": "array",
            "items": {
              "type": "object",
              "required": ["text"],
              "properties": {
                "text": { "type": "string" },
                "evidence": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "required": ["segmentId", "startMs", "endMs", "evidenceTextSnapshot"],
                    "properties": {
                      "segmentId": { "type": "string" },
                      "startMs": { "type": "integer" },
                      "endMs": { "type": "integer" },
                      "evidenceTextSnapshot": { "type": "string" }
                    }
                  }
                },
                "assigneeDisplayName": { "type": "string" },
                "dueDate": { "type": "string", "format": "date" },
                "severity": { "type": "string", "enum": ["HIGH", "MEDIUM", "LOW"] },
                "status": { "type": "string", "enum": ["PROPOSED", "SUGGESTED", "OPEN", "UNCONFIRMED"] }
              }
            }
          }
        }
      }
    },
    "artifactMetadata": {
      "type": "object",
      "required": ["promptTemplateId", "promptTemplateVersion"],
      "properties": {
        "promptTemplateId": { "type": "string" },
        "promptTemplateVersion": { "type": "string" }
      }
    }
  }
}$schema$::jsonb,
  'ACTIVE'
WHERE NOT EXISTS (
  SELECT 1 FROM prompt_templates WHERE task_name = 'MINUTES_SUMMARY'
);

INSERT INTO prompt_templates (
  id, tenant_id, task_name, version, major_version, minor_version, patch_version,
  template_body, json_schema, status
)
SELECT
  'ptpl_item_extraction_v0_1_0', NULL, 'ITEM_EXTRACTION', '0.1.0', 0, 1, 0,
  $body$你是一个专业的会议信息抽取助手。请从会议转录中抽取待办事项、决策和风险。

要求：
1. 每一条都必须附带原文依据（evidence）：segmentId 使用转录片段行首方括号内的片段 ID，并逐字引用 evidenceTextSnapshot。
2. 输出一个 JSON 对象，包含三个数组字段：actionItems、decisions、risks；没有内容的数组输出 []。
3. actionItems 每项包含 title、description、ownerRawText（转录中提到的负责人原文，如有）、deadlineRawText（转录中提到的期限原文，如有）、evidence。
4. decisions 每项包含 title、description、evidence。
5. risks 每项包含 title、description、severity（HIGH/MEDIUM/LOW）、evidence。

## 会议信息
- 会议标题：{{meetingTitle}}
- 会议时间：{{meetingDate}}

## 转录片段
{{transcriptSegments}}

请输出 JSON。$body$,
  $schema${
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "item_extraction_v1",
  "type": "object",
  "required": ["actionItems", "decisions", "risks"],
  "properties": {
    "actionItems": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["title"],
        "properties": {
          "title": { "type": "string" },
          "description": { "type": "string" },
          "ownerRawText": { "type": "string" },
          "deadlineRawText": { "type": "string" },
          "evidence": {
            "type": "array",
            "items": {
              "type": "object",
              "required": ["segmentId", "evidenceTextSnapshot"],
              "properties": {
                "segmentId": { "type": "string" },
                "startMs": { "type": "integer" },
                "endMs": { "type": "integer" },
                "evidenceTextSnapshot": { "type": "string" }
              }
            }
          }
        }
      }
    },
    "decisions": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["title"],
        "properties": {
          "title": { "type": "string" },
          "description": { "type": "string" },
          "evidence": {
            "type": "array",
            "items": {
              "type": "object",
              "required": ["segmentId", "evidenceTextSnapshot"],
              "properties": {
                "segmentId": { "type": "string" },
                "startMs": { "type": "integer" },
                "endMs": { "type": "integer" },
                "evidenceTextSnapshot": { "type": "string" }
              }
            }
          }
        }
      }
    },
    "risks": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["title"],
        "properties": {
          "title": { "type": "string" },
          "description": { "type": "string" },
          "severity": { "type": "string", "enum": ["HIGH", "MEDIUM", "LOW"] },
          "evidence": {
            "type": "array",
            "items": {
              "type": "object",
              "required": ["segmentId", "evidenceTextSnapshot"],
              "properties": {
                "segmentId": { "type": "string" },
                "startMs": { "type": "integer" },
                "endMs": { "type": "integer" },
                "evidenceTextSnapshot": { "type": "string" }
              }
            }
          }
        }
      }
    }
  }
}$schema$::jsonb,
  'ACTIVE'
WHERE NOT EXISTS (
  SELECT 1 FROM prompt_templates WHERE task_name = 'ITEM_EXTRACTION'
);
