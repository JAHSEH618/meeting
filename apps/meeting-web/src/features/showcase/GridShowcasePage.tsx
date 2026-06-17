import { HeroSection } from '@shared/components/HeroSection';

export function GridShowcasePage() {
  return (
    <div className="page page--workbench">
      <HeroSection
        label="LAYOUT SYSTEM"
        title="会议模块布局"
        subtitle="跨列关系由业务职责决定：入口页强调总览，工作页强调推进，合规页强调扫描。模块不用装饰标识抢注意力。"
      />

      <section className="meeting-modules grid-12" aria-label="入口页模块布局">
        <article className="module-card module-card--wide span-8">
          <div>
            <p className="module-card__eyebrow">ENTRY / 8 COLUMNS</p>
            <h2>会议入口总览</h2>
            <p>宽模块承载系统当前状态、继续处理入口和最近会议线索，承担第一屏的信息重心。</p>
          </div>
          <div className="module-card__rail">
            <span>总览</span>
            <span>状态</span>
            <span>搜索</span>
            <span>继续处理</span>
          </div>
        </article>

        <article className="module-card span-4">
          <p className="module-card__eyebrow">ACTION / 4 COLUMNS</p>
          <h2>主操作</h2>
          <p>新建会议、文档库和问答入口放在侧重位置，但不做成独立品牌区。</p>
        </article>
      </section>

      <section className="meeting-modules grid-12" aria-label="工作台模块布局">
        <article className="module-card span-6">
          <p className="module-card__eyebrow">WORKBENCH / MAIN</p>
          <h2>处理内容</h2>
          <p>转录、纪要、行动项等长内容使用 6 到 8 列主区域，确保阅读和编辑稳定。</p>
        </article>
        <article className="module-card span-6">
          <p className="module-card__eyebrow">WORKBENCH / SIDE</p>
          <h2>状态上下文</h2>
          <p>任务阶段、引用来源、发言人确认等辅助信息靠右组织，减少页面跳转。</p>
        </article>
      </section>

      <section className="meeting-modules grid-12" aria-label="合规模块布局">
        <article className="module-card span-4">
          <p className="module-card__eyebrow">DENSE / HOLDS</p>
          <h2>法律保留</h2>
          <p>紧凑标题下直接露出表格，优先支持扫描和筛选。</p>
        </article>
        <article className="module-card span-4">
          <p className="module-card__eyebrow">DENSE / DELETE</p>
          <h2>删除任务</h2>
          <p>危险操作保留明确按钮层级，不用视觉装饰提高紧张感。</p>
        </article>
        <article className="module-card span-4">
          <p className="module-card__eyebrow">DENSE / AUDIT</p>
          <h2>审计事件</h2>
          <p>数据表承载细节，模块卡只说明页面密度和信息职责。</p>
        </article>
      </section>

      <section className="glass-panel glass-panel--compact stack">
        <h2 className="glass-panel__title">响应式策略</h2>
        <p className="glass-panel__body">
          宽屏使用 12 列建立主次关系；中屏收敛到 6 列；移动端单列堆叠。所有模块保持文字优先，不额外生成装饰位。
        </p>
      </section>
    </div>
  );
}
