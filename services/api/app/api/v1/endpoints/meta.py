"""学科与知识模块字典（`GET /meta/subjects`）。

字典用途：

1. 前端「导入」页选择学科、按模块筛选失分点；
2. AI 分析时把模块术语与章节名喂给模型（`AnalysisInput.knowledge_modules`），
   让返回的 `module` / `chapter` 与产品内的知识体系对齐，便于统计与展示。

数据落 `subjects` / `knowledge_modules` 两张表：首次访问时**幂等补齐**（已存在的跳过），
因此重复调用、并发调用都不会产生重复数据。新增学科只需改本文件的目录表。
"""

from __future__ import annotations

import asyncio
import logging
from typing import Any

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import DbSession
from app.core.response import EnvelopeRoute
from app.core.utils import new_id
from app.models.exam import KnowledgeModule, Subject
from app.schemas.misc import ModuleMeta, SubjectMeta
from fastapi import APIRouter

logger = logging.getLogger("app.api.meta")

router = APIRouter(route_class=EnvelopeRoute, prefix="/meta", tags=["meta"])

SUBJECT_CATALOG: tuple[dict[str, Any], ...] = (
    {
        "code": "chinese",
        "name": "语文",
        "sort": 10,
        "modules": {
            "现代文阅读": ["论述类文本阅读", "文学类文本阅读", "实用类文本阅读", "信息类文本阅读"],
            "古诗文阅读": ["文言文阅读", "古代诗歌鉴赏", "名篇名句默写", "文言实词与句式"],
            "语言文字运用": ["成语与词语辨析", "病句辨析与修改", "语言表达连贯得体", "修辞与句式仿写"],
            "写作": ["材料作文审题立意", "议论文结构与论证", "记叙文写作", "作文素材与语言"],
        },
    },
    {
        "code": "math",
        "name": "数学",
        "sort": 20,
        "modules": {
            "集合与常用逻辑用语": ["集合的概念与运算", "充分必要条件", "全称量词与存在量词"],
            "函数与导数": ["函数的概念与性质", "指数函数与对数函数", "三角函数与解三角形", "导数的运算", "导数的应用"],
            "数列": ["等差数列", "等比数列", "数列求和与递推"],
            "立体几何": ["空间点线面位置关系", "空间向量与立体几何", "三视图与表面积体积"],
            "解析几何": ["直线与圆", "椭圆", "双曲线与抛物线", "直线与圆锥曲线的位置关系"],
            "概率与统计": ["排列组合与二项式定理", "随机变量及其分布", "统计与统计案例"],
        },
    },
    {
        "code": "english",
        "name": "英语",
        "sort": 30,
        "modules": {
            "听力": ["短对话理解", "长对话与独白理解"],
            "阅读理解": ["细节理解题", "推理判断题", "主旨大意题", "词义猜测题", "七选五"],
            "语言知识运用": ["完形填空", "语法填空", "短文改错"],
            "写作": ["应用文写作", "读后续写", "概要写作"],
        },
    },
    {
        "code": "physics",
        "name": "物理",
        "sort": 40,
        "modules": {
            "力学": ["运动的描述与匀变速直线运动", "相互作用与牛顿运动定律", "曲线运动与万有引力", "机械能守恒定律", "动量与碰撞"],
            "电磁学": ["静电场", "恒定电流", "磁场与安培力", "电磁感应", "交变电流"],
            "热学与近代物理": ["分子动理论与气体实验定律", "热力学定律", "光电效应与原子结构", "原子核与核反应"],
            "实验与探究": ["力学实验", "电学实验", "误差分析与数据处理"],
        },
    },
    {
        "code": "chemistry",
        "name": "化学",
        "sort": 50,
        "modules": {
            "化学基本概念": ["物质的分类与化学计量", "离子反应", "氧化还原反应"],
            "元素化合物": ["金属及其化合物", "非金属及其化合物", "无机推断"],
            "化学反应原理": ["化学反应速率与化学平衡", "水溶液中的离子平衡", "电化学基础", "反应热与盖斯定律"],
            "有机化学": ["有机物的结构与命名", "烃与卤代烃", "烃的含氧衍生物", "有机合成与推断"],
            "化学实验": ["实验基本操作", "物质的检验与分离提纯", "实验方案设计与评价"],
        },
    },
    {
        "code": "biology",
        "name": "生物",
        "sort": 60,
        "modules": {
            "分子与细胞": ["细胞的分子组成", "细胞的结构与功能", "细胞的代谢", "细胞的生命历程"],
            "遗传与进化": ["遗传的基本规律", "基因的本质与表达", "变异与育种", "生物进化理论"],
            "稳态与调节": ["人体内环境与稳态", "神经调节", "体液调节", "免疫调节", "植物激素调节"],
            "生态与环境": ["种群与群落", "生态系统的结构与功能", "生态环境保护"],
            "生物技术与实验": ["微生物的培养与应用", "基因工程", "实验设计与探究"],
        },
    },
    {
        "code": "politics",
        "name": "政治",
        "sort": 70,
        "modules": {
            "经济生活": ["生活与消费", "生产劳动与经营", "收入与分配", "发展社会主义市场经济"],
            "政治生活": ["公民的政治生活", "为人民服务的政府", "发展社会主义民主政治", "当代国际社会"],
            "文化生活": ["文化与生活", "文化传承与创新", "中华文化与民族精神", "发展中国特色社会主义文化"],
            "生活与哲学": ["生活智慧与时代精神", "探索世界与追求真理", "思想方法与创新意识", "认识社会与价值选择"],
        },
    },
    {
        "code": "history",
        "name": "历史",
        "sort": 80,
        "modules": {
            "中国古代史": ["先秦时期", "秦汉与魏晋南北朝", "隋唐与宋元", "明清时期"],
            "中国近现代史": ["晚清时期", "中华民国时期", "中华人民共和国史"],
            "世界古代史": ["古代文明的产生与发展", "中古时期的世界"],
            "世界近现代史": ["走向整体的世界", "资本主义制度的确立", "工业革命与马克思主义", "两次世界大战", "世界格局的演变"],
        },
    },
    {
        "code": "geography",
        "name": "地理",
        "sort": 90,
        "modules": {
            "自然地理": ["地球运动与地图", "大气运动与气候", "水循环与洋流", "地表形态的塑造", "自然环境的整体性与差异性"],
            "人文地理": ["人口与城市", "农业与工业区位", "交通与区域发展"],
            "区域地理": ["中国地理", "世界地理", "区域可持续发展"],
            "地理信息技术": ["遥感与全球定位", "地理信息系统"],
        },
    },
)

SUBJECT_NAME_BY_CODE: dict[str, str] = {str(entry["code"]): str(entry["name"]) for entry in SUBJECT_CATALOG}
"""学科 code -> 中文名（服务层组装 prompt 时也会用到）。"""

_seed_lock = asyncio.Lock()
_seeded = False


def subject_name(code: str) -> str:
    """取学科中文名；未知 code 原样返回。"""
    return SUBJECT_NAME_BY_CODE.get(code, code)


async def ensure_dictionary(db: AsyncSession, *, force: bool = False) -> None:
    """幂等补齐学科与知识模块字典（不提交，由调用方决定事务边界）。"""
    global _seeded
    if _seeded and not force:
        return

    async with _seed_lock:
        if _seeded and not force:
            return

        existing_subjects = {code: id_ for id_, code in (await db.execute(select(Subject.id, Subject.code))).all()}
        existing_modules = {
            (subject_code, name): parent_id
            for subject_code, name, parent_id in (
                await db.execute(
                    select(KnowledgeModule.subject_code, KnowledgeModule.name, KnowledgeModule.parent_id).where(
                        KnowledgeModule.level == 1
                    )
                )
            ).all()
        }

        created_subjects = 0
        created_modules = 0
        for entry in SUBJECT_CATALOG:
            code = str(entry["code"])
            if code not in existing_subjects:
                db.add(Subject(id=new_id(), code=code, name=str(entry["name"]), sort=int(entry["sort"])))
                created_subjects += 1

            modules: dict[str, list[str]] = dict(entry["modules"])
            for order, (module_name, chapters) in enumerate(modules.items(), start=1):
                if (code, module_name) in existing_modules:
                    continue
                module_id = new_id()
                db.add(
                    KnowledgeModule(
                        id=module_id,
                        subject_code=code,
                        name=module_name,
                        parent_id=None,
                        level=1,
                        sort=order,
                    )
                )
                for chapter_order, chapter in enumerate(chapters, start=1):
                    db.add(
                        KnowledgeModule(
                            id=new_id(),
                            subject_code=code,
                            name=chapter,
                            parent_id=module_id,
                            level=2,
                            sort=chapter_order,
                        )
                    )
                created_modules += 1

        await db.flush()
        _seeded = True
        if created_subjects or created_modules:
            logger.info("学科字典已补齐：新增 %s 个学科、%s 个模块", created_subjects, created_modules)


async def load_subject_meta(db: AsyncSession) -> list[SubjectMeta]:
    """从数据库读出「学科 → 模块 → 章节」三级字典。"""
    await ensure_dictionary(db)

    subjects = list(await db.scalars(select(Subject).order_by(Subject.sort, Subject.code)))
    modules = list(
        await db.scalars(
            select(KnowledgeModule).where(KnowledgeModule.level == 1).order_by(KnowledgeModule.sort, KnowledgeModule.name)
        )
    )
    chapters = list(
        await db.scalars(
            select(KnowledgeModule).where(KnowledgeModule.level == 2).order_by(KnowledgeModule.sort, KnowledgeModule.name)
        )
    )

    chapters_by_parent: dict[str | None, list[str]] = {}
    for chapter in chapters:
        chapters_by_parent.setdefault(chapter.parent_id, []).append(chapter.name)

    meta: list[SubjectMeta] = []
    for subject in subjects:
        module_items = [
            ModuleMeta(name=module.name, chapters=chapters_by_parent.get(module.id, []))
            for module in modules
            if module.subject_code == subject.code
        ]
        meta.append(SubjectMeta(code=subject.code, name=subject.name, modules=module_items))
    return meta


@router.get("/subjects", response_model=list[SubjectMeta], summary="学科与知识模块字典")
async def list_subjects(db: DbSession) -> list[SubjectMeta]:
    """返回全部学科及其模块 / 章节树（首次访问时自动灌入字典）。"""
    data = await load_subject_meta(db)
    await db.commit()
    return data


async def dictionary_size(db: AsyncSession) -> int:
    """字典中学科数量（供健康检查 / 脚本打印用）。"""
    return int(await db.scalar(select(func.count()).select_from(Subject)) or 0)
