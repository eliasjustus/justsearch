#!/usr/bin/env node
// transcript-spine.mjs — condenses a transcript to a readable per-turn spine
// for human/agent review (tempdoc 743's evidence lane). RESCUED verbatim
// from session scratchpad (a6d2af56, spine.mjs) into the repo (tempdoc 743
// second wave, Slice 3, "Shared substrate" — homed alongside the other
// evidence-lane tooling). Mechanical extraction only: no summarization, no
// judgment, no interpretation of the content it condenses.
//
// Usage: node transcript-spine.mjs <transcript.jsonl> <out.txt> [excerptLen]
import fs from 'node:fs';
import readline from 'node:readline';

const [,, inPath, outPath, exLenArg] = process.argv;
const EX = Number(exLenArg || 400);

const out = fs.createWriteStream(outPath);
const rl = readline.createInterface({ input: fs.createReadStream(inPath), crlfDelay: Infinity });

let i = 0, prevTs = null;
const toolCounts = {}, stats = { lines: 0, errors: 0, compactions: 0, hookNotes: 0, spawns: 0 };

const clip = (s, n = EX) => {
  if (s == null) return '';
  s = String(s).replace(/\s+/g, ' ').trim();
  return s.length > n ? s.slice(0, n) + `…[+${s.length - n}ch]` : s;
};
const fmtTs = ts => ts ? ts.slice(5, 19).replace('T', ' ') : '??';

rl.on('line', line => {
  i++; stats.lines++;
  let j; try { j = JSON.parse(line); } catch { out.write(`#${i} [UNPARSEABLE]\n`); return; }
  const ts = j.timestamp || null;
  let gap = '';
  if (ts && prevTs) {
    const d = (new Date(ts) - new Date(prevTs)) / 1000;
    if (d > 120) gap = `\n  ===== GAP ${Math.round(d / 60)}min =====`;
  }
  if (ts) prevTs = ts;
  const side = j.isSidechain ? ' [SIDECHAIN]' : '';
  const t = j.type;

  if (t === 'summary') { out.write(`#${i} SUMMARY: ${clip(j.summary, 200)}\n`); stats.compactions++; return; }
  if (t === 'system') {
    stats.hookNotes++;
    out.write(`${gap}\n#${i} ${fmtTs(ts)} SYSTEM${side} ${j.subtype || ''}: ${clip(j.content || j.message || JSON.stringify(j).slice(0, 200), 300)}\n`);
    return;
  }
  const m = j.message;
  if (!m) { out.write(`#${i} ${fmtTs(ts)} [${t}] (no message)\n`); return; }
  const content = m.content;

  if (t === 'user') {
    if (typeof content === 'string') {
      out.write(`${gap}\n#${i} ${fmtTs(ts)} USER${side}: ${clip(content)}\n`);
    } else if (Array.isArray(content)) {
      for (const b of content) {
        if (b.type === 'text') out.write(`${gap}\n#${i} ${fmtTs(ts)} USER${side}: ${clip(b.text)}\n`);
        else if (b.type === 'tool_result') {
          const err = b.is_error ? ' !!ERROR' : '';
          if (b.is_error) stats.errors++;
          let txt = '';
          if (typeof b.content === 'string') txt = b.content;
          else if (Array.isArray(b.content)) txt = b.content.map(c => c.text || '').join(' ');
          const len = txt.length;
          out.write(`#${i} ${fmtTs(ts)}   -> result${err} (${len}ch): ${clip(txt, b.is_error ? EX : 200)}\n`);
        }
      }
    }
    return;
  }
  if (t === 'assistant') {
    const model = m.model || '?';
    const u = m.usage || {};
    const usage = `out:${u.output_tokens ?? '?'} cr:${u.cache_read_input_tokens ?? 0}`;
    for (const b of Array.isArray(content) ? content : []) {
      if (b.type === 'text') out.write(`${gap}\n#${i} ${fmtTs(ts)} ASSISTANT${side} [${model} ${usage}]: ${clip(b.text)}\n`);
      else if (b.type === 'thinking') out.write(`#${i} ${fmtTs(ts)} (thinking ${String(b.thinking || '').length}ch)${side}: ${clip(b.thinking, 150)}\n`);
      else if (b.type === 'tool_use') {
        toolCounts[b.name] = (toolCounts[b.name] || 0) + 1;
        if (b.name === 'Task' || b.name === 'Agent') stats.spawns++;
        let inp = '';
        try {
          const o = { ...b.input };
          if (o.prompt) o.prompt = clip(o.prompt, b.name === 'Task' || b.name === 'Agent' ? 500 : 150);
          if (o.content) o.content = `[${String(b.input.content).length}ch]`;
          if (o.new_string) o.new_string = `[${String(b.input.new_string).length}ch]`;
          if (o.old_string) o.old_string = `[${String(b.input.old_string).length}ch]`;
          inp = clip(JSON.stringify(o), 450);
        } catch { inp = '?'; }
        out.write(`${gap}\n#${i} ${fmtTs(ts)} TOOL${side} ${b.name} [${model} ${usage}]: ${inp}\n`);
      }
    }
    return;
  }
  out.write(`#${i} ${fmtTs(ts)} [${t}]${side}: ${clip(JSON.stringify(j), 200)}\n`);
});

rl.on('close', () => {
  out.write(`\n\n===== STATS =====\n${JSON.stringify(stats, null, 1)}\nTool call counts: ${JSON.stringify(toolCounts, null, 1)}\n`);
  out.end();
  console.log(`done: ${i} lines -> ${outPath}`);
});
