import { useState } from 'react';
import { HeroSection } from '@shared/components/HeroSection';
import { StatsGrid, StatCard } from '@shared/components/StatsGrid';
import { GlassModal } from '@shared/components/GlassModal';

export function DesignShowcasePage() {
  const [isModalOpen, setIsModalOpen] = useState(false);

  return (
    <div className="page page--workbench">
      <HeroSection
        label="视觉方向"
        title="会议工作台视觉方案"
        subtitle="浅色玻璃、强标题、12 列业务模块。模块只表达职责和数据关系，不再放独立装饰标识。"
        actions={
          <>
            <button className="button button--primary" onClick={() => setIsModalOpen(true)}>
              查看面板层级
            </button>
            <button className="button button--ghost">对照入口页</button>
          </>
        }
      />

      <StatsGrid>
        <StatCard value="4" label="页面密度层级" variant="accent" />
        <StatCard value="12" label="业务栅格列数" variant="accent" />
        <StatCard value="0" label="装饰图形数量" variant="accent" />
      </StatsGrid>

      <section className="meeting-modules grid-12" aria-label="视觉模块预览">
        <article className="module-card module-card--wide span-6">
          <div>
            <p className="module-card__eyebrow">入口 / 首屏</p>
            <h2>入口页负责定调</h2>
            <p>使用大标题、黑色主按钮和宽玻璃面板建立第一印象，内容直接指向会议创建与继续处理。</p>
          </div>
          <div className="module-card__rail">
            <span>标题</span>
            <span>概览</span>
            <span>模块</span>
            <span>列表</span>
          </div>
        </article>

        <article className="module-card module-card--wide span-6">
          <div>
            <p className="module-card__eyebrow">工作台 / 流程</p>
            <h2>工作页负责推进</h2>
            <p>上传、转录、纪要、知识问答等页面保留操作密度，玻璃只承担分区和焦点，不制造额外装饰。</p>
          </div>
          <div className="module-card__meta">
            <span>8/4 主侧栏</span>
            <span>长内容列表</span>
            <span>轻微悬停反馈</span>
          </div>
        </article>

        <article className="module-card span-4">
          <p className="module-card__eyebrow">密集</p>
          <h2>合规页收敛高度</h2>
          <p>表格首屏必须露出，标题和卡片更紧凑，主按钮不泛滥。</p>
        </article>

        <article className="module-card span-4">
          <p className="module-card__eyebrow">文字</p>
          <h2>中文标题不拉字距</h2>
          <p>大字号靠字重和留白建立冲击，不用装饰性字距模拟科技感。</p>
        </article>

        <article className="module-card span-4">
          <p className="module-card__eyebrow">层级</p>
          <h2>玻璃只做层级</h2>
          <p>没有圆形图标、品牌徽章或装饰标识，模块靠内容职责区分。</p>
        </article>
      </section>

      <section className="glass-panel glass-panel--table">
        <table className="data-table">
          <thead>
            <tr>
              <th>页面类型</th>
              <th>标题强度</th>
              <th>模块表达</th>
              <th>主要目标</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>入口页</td>
              <td>最大</td>
              <td>不规则业务模块</td>
              <td>开始和继续会议</td>
            </tr>
            <tr>
              <td>工作页</td>
              <td>中等</td>
              <td>主内容 + 状态侧栏</td>
              <td>推进处理流</td>
            </tr>
            <tr>
              <td>合规页</td>
              <td>收敛</td>
              <td>紧凑表格面板</td>
              <td>扫描和审计</td>
            </tr>
          </tbody>
        </table>
      </section>

      <GlassModal
        isOpen={isModalOpen}
        onClose={() => setIsModalOpen(false)}
        title="会议处理详情"
        footer={
          <>
            <button className="button button--ghost" onClick={() => setIsModalOpen(false)}>
              取消
            </button>
            <button className="button button--primary" onClick={() => setIsModalOpen(false)}>
              确认
            </button>
          </>
        }
      >
        <p>Modal 用于确认、导出和高风险操作。内容优先，玻璃背景只负责把它从页面层级中抬起来。</p>
        <p>关闭按钮、背景点击和 ESC 逻辑保持一致，不在弹层里加入额外品牌图形。</p>
      </GlassModal>
    </div>
  );
}
