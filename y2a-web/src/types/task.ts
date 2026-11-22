/** 前端任务类型（与 Java Task 对齐）。 */
export interface Task {
  id: string
  youtubeUrl: string
  uploadTarget: string
  status: string
  createdAt: string
  updatedAt: string
  videoTitleOriginal?: string
  videoTitleTranslated?: string
  descriptionOriginal?: string
  descriptionTranslated?: string
  tagsGenerated?: string[]
  videoPathLocal?: string
  coverPathLocal?: string
  selectedPartitionId?: string
  recommendedPartitionId?: string
  moderationResult?: Record<string, unknown> | string
  uploadProgress?: string
  errorMessage?: string
  acfunUploadResponse?: Record<string, unknown>
  bilibiliUploadResponse?: Record<string, unknown>
  douyinUploadResponse?: Record<string, unknown>
}

export interface TaskPage {
  records: Task[]
  total: number
  current: number
  size: number
}

export const STATUS_LABELS: Record<string, string> = {
  pending: '等待中',
  downloading: '下载中',
  downloaded: '已下载',
  asr_transcribing: '语音识别中',
  translating_subtitle: '字幕翻译中',
  encoding_video: '转码中',
  translating: '翻译中',
  tagging: '生成标签中',
  partitioning: '推荐分区中',
  moderating: '内容审核中',
  awaiting_manual_review: '待人工审核',
  ready_for_upload: '待上传',
  uploading: '上传中',
  completed: '已完成',
  failed: '失败',
}
