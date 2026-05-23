import { ref } from 'vue';

/** 单个附件的运行时数据。 */
export interface Attachment {
  name: string;
  type: string;
  kind: 'text' | 'image' | 'binary';
  content?: string;
  size: number;
  loading?: boolean;
  error?: string;
  truncated?: boolean;
}

const MAX_TEXT_BYTES = 200_000;  // 200KB per text file
const MAX_IMAGE_BYTES = 8_000_000; // 8MB per image
const MAX_DOCX_BYTES = 12_000_000; // 12MB per docx
const TEXT_EXTS = [
  'txt','md','markdown','json','csv','tsv','log','xml','yaml','yml','html','htm',
  'js','ts','tsx','jsx','py','java','c','cpp','h','hpp','go','rs','rb','sh','sql',
  'toml','ini','env','vue','css','scss','less'
];
const IMAGE_EXTS = ['png','jpg','jpeg','gif','webp','bmp'];

const readAsText = (f: File): Promise<string> => new Promise((resolve, reject) => {
  const r = new FileReader();
  r.onload = () => resolve(String(r.result || ''));
  r.onerror = () => reject(r.error);
  r.readAsText(f);
});

const readAsDataURL = (f: File): Promise<string> => new Promise((resolve, reject) => {
  const r = new FileReader();
  r.onload = () => resolve(String(r.result || ''));
  r.onerror = () => reject(r.error);
  r.readAsDataURL(f);
});

const extractDocxText = async (f: File): Promise<{ text: string; truncated: boolean }> => {
  const form = new FormData();
  form.append('file', f);
  const resp = await fetch('/api/extract/docx-text', { method: 'POST', body: form });
  if (!resp.ok) {
    let msg = `HTTP ${resp.status}`;
    try { msg = (await resp.json())?.error || msg; } catch { /* noop */ }
    throw new Error(msg);
  }
  const j = await resp.json();
  return { text: String(j.text || ''), truncated: !!j.truncated };
};

/**
 * 附件状态与读取逻辑(文本/图片/二进制 + 体积校验)。
 */
export function useAttachments() {
  const atts = ref<Attachment[]>([]);

  const addFile = async (f: File) => {
    const ext = f.name.split('.').pop()?.toLowerCase() || '';
    const isImage = IMAGE_EXTS.includes(ext) || f.type.startsWith('image/');
    const isDocx = !isImage && (
      ext === 'docx' ||
      f.type === 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'
    );
    const isText = !isImage && !isDocx && (TEXT_EXTS.includes(ext) || f.type.startsWith('text/') || f.type === 'application/json');

    const att: Attachment = {
      name: f.name,
      type: ext,
      // docx 抽取后是纯文本,统一归到 text,便于后续按文本附件拼到 prompt
      kind: isImage ? 'image' : ((isText || isDocx) ? 'text' : 'binary'),
      size: f.size,
      loading: true,
    };
    atts.value.push(att);

    try {
      if (isImage) {
        if (f.size > MAX_IMAGE_BYTES) {
          att.error = `图片超过 ${Math.round(MAX_IMAGE_BYTES / 1024 / 1024)}MB 限制`;
        } else {
          att.content = await readAsDataURL(f);
        }
      } else if (isDocx) {
        if (f.size > MAX_DOCX_BYTES) {
          att.error = `DOCX 超过 ${Math.round(MAX_DOCX_BYTES / 1024 / 1024)}MB 限制`;
        } else {
          const r = await extractDocxText(f);
          att.content = r.text;
          if (r.truncated) att.truncated = true;
        }
      } else if (isText) {
        if (f.size > MAX_TEXT_BYTES) {
          const slice = f.slice(0, MAX_TEXT_BYTES);
          att.content = await readAsText(new File([slice], f.name));
          att.truncated = true;
        } else {
          att.content = await readAsText(f);
        }
      } else {
        att.error = `不支持的文件类型 (.${ext}),请上传文本/图片/DOCX`;
      }
    } catch (e: any) {
      att.error = '读取失败: ' + (e?.message || e);
    } finally {
      att.loading = false;
    }
  };

  const clearAttachments = () => { atts.value = []; };

  return { atts, addFile, clearAttachments };
}
