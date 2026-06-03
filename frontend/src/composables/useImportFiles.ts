import { ref, computed } from 'vue';

/**
 * 文档导入对话框的"文件 / URL 拾取"部分。
 * 负责文件类型校验、去重、数量上限,以及 URL 文本解析与上限提示。
 */
export function useImportFiles() {
  const files = ref<File[]>([]);
  const urlInput = ref('');
  const errorMsg = ref('');

  const parsedUrls = computed(() =>
    urlInput.value.split(/[\s,]+/).map(u => u.trim()).filter(Boolean));
  const URL_LIMIT = 5;
  const urlOverLimit = computed(() => parsedUrls.value.length > URL_LIMIT);

  const SOURCE_ICONS: Record<string, string> = {
    image: '🖼',
    pdf: '📄',
    docx: '📝',
    audio: '🎵',
    url: '🔗',
  };
  const iconForSource = (type?: string) => SOURCE_ICONS[type || ''] || '⛔';

  const fmtSize = (n: number) => {
    if (n < 1024) return n + ' B';
    if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
    return (n / 1024 / 1024).toFixed(2) + ' MB';
  };

  const addFile = (f: File) => {
    if (files.value.length >= 8) return;
    const lname = f.name.toLowerCase();
    const ok = f.type.startsWith('image/')
            || f.type.startsWith('audio/')
            || f.type === 'application/pdf' || lname.endsWith('.pdf')
            || lname.endsWith('.docx')
            || /\.(mp3|wav|m4a|aac|flac|ogg|oga|opus|amr|wma|webm)$/.test(lname)
            || f.type === 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';
    if (!ok) { errorMsg.value = `不支持的文件类型：${f.name}`; return; }
    if (files.value.find(x => x.name === f.name && x.size === f.size)) return;
    files.value.push(f);
    errorMsg.value = '';
  };

  const onPick = (e: Event) => {
    const input = e.target as HTMLInputElement;
    if (!input.files) return;
    for (const f of Array.from(input.files)) addFile(f);
    input.value = '';
  };
  const onDrop = (e: DragEvent) => {
    e.preventDefault();
    if (!e.dataTransfer?.files) return;
    for (const f of Array.from(e.dataTransfer.files)) addFile(f);
  };
  const removeFile = (idx: number) => { files.value.splice(idx, 1); };

  const resetFiles = () => {
    files.value = [];
    urlInput.value = '';
    errorMsg.value = '';
  };

  return {
    files, urlInput, errorMsg,
    parsedUrls, urlOverLimit, URL_LIMIT,
    SOURCE_ICONS, iconForSource, fmtSize,
    addFile, onPick, onDrop, removeFile,
    resetFiles,
  };
}
