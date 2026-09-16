"""后台任务包（asyncio 协程调度 + `import_tasks` 状态表）。

不引入 Celery：本项目后台任务量小（OCR 识别与 AI 分析），进程内协程调度即可，
任务状态落库、前端轮询，详见 `app.tasks.queue`。
"""
