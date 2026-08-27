import { createRequire } from 'module';
const require = createRequire('E:/agent_projct/video_reader/client/package.json');
const { parse, compileScript, compileTemplate } = require('@vue/compiler-sfc');
const fs = require('fs');
const path = require('path');
const files = ['App.vue', 'views/LoginView.vue', 'views/LibraryView.vue', 'views/WorkspaceView.vue', 'views/TrustTraceView.vue', 'views/KnowledgeView.vue', 'views/SettingsView.vue', 'layouts/GlobalShell.vue', 'components/UploadModal.vue', 'components/ConfirmDialog.vue', 'components/EditableTitle.vue'];
let ok = true;
for (const f of files) {
  const p = path.resolve('E:/agent_projct/video_reader/client/src', f);
  const src = fs.readFileSync(p, 'utf8');
  const { descriptor, errors } = parse(src);
  if (errors.length) { ok = false; console.log('PARSE FAIL', f, errors); continue; }
  try {
    if (descriptor.script || descriptor.scriptSetup) {
      compileScript(descriptor, { id: f });
    }
    if (descriptor.template) {
      const r = compileTemplate({ source: descriptor.template.content, filename: f, id: f });
      if (r.errors.length) { ok = false; console.log('TEMPLATE FAIL', f, r.errors); continue; }
    }
    console.log('OK', f);
  } catch (e) { ok = false; console.log('COMPILE FAIL', f, e.message); }
}
console.log(ok ? 'ALL SFC VALID' : 'HAS ERRORS');
