import {readFile,writeFile} from 'node:fs/promises';
const policy=JSON.parse(await readFile(new URL('../../shared/content-quality-v1.json',import.meta.url),'utf8'));
const literal=value=>JSON.stringify(value).replaceAll('$','\\$');
const output=`package io.legado.app.model.cloud\n\n/** Generated from server/shared/content-quality-v1.json by compile-content-quality.mjs. */\ninternal object CloudContentQualityRules {\n    const val VERSION = ${literal(policy.version)}\n    const val MIGRATION_MINIMUM = ${policy.migrationMinimum}\n    const val SHORT_TEMPLATE_LIMIT = ${policy.shortTemplateLimit}\n    const val TEMPLATE_LINE_LIMIT = ${policy.templateLineLimit}\n    val patterns = listOf(\n${policy.patterns.map(pattern=>'        '+literal(pattern)).join(',\n')}\n    ).map { Regex(it, RegexOption.IGNORE_CASE) }\n}\n`;
await writeFile(new URL('../../../upstream/app/src/main/java/io/legado/app/model/cloud/CloudContentQualityRules.kt',import.meta.url),output);
