import { useState } from 'react';
import { HeroSection } from '@shared/components/HeroSection';
import { StatsGrid, StatCard } from '@shared/components/StatsGrid';
import { GlassModal } from '@shared/components/GlassModal';

/**
 * 设计系统展示页面
 * 展示所有极致毛玻璃组件
 */
export function DesignShowcasePage() {
  const [isModalOpen, setIsModalOpen] = useState(false);

  return (
    <div className="page page--workbench">
      {/* Hero Section */}
      <HeroSection
        label="DESIGN SYSTEM"
        title="极致前卫毛玻璃设计"
        subtitle="120px 超大标题 · blur(80px) 极致毛玻璃 · Apple 质感"
        actions={
          <>
            <button className="button button--primary" onClick={() => setIsModalOpen(true)}>
              查看 Modal 示例
            </button>
            <button className="button button--ghost">了解更多</button>
          </>
        }
      />

      {/* Stats Grid */}
      <StatsGrid>
        <StatCard value="120px" label="超大标题" variant="accent" />
        <StatCard value="80px" label="模糊强度" variant="accent" />
        <StatCard value="9.2:1" label="字号对比" variant="accent" />
      </StatsGrid>

      {/* Glass Cards Demo */}
      <section className="card stack">
        <h2 style={{ fontSize: 'var(--text-xl)', fontWeight: 700 }}>
          毛玻璃卡片示例
        </h2>
        <p style={{ color: 'var(--text-light)' }}>
          这个卡片使用 blur(40px) 毛玻璃效果，背景透明度 70%，内高光边缘处理。
        </p>
        <div style={{ display: 'flex', gap: 'var(--space-4)', flexWrap: 'wrap' }}>
          <button className="button button--primary">主要按钮</button>
          <button className="button button--ghost">次要按钮</button>
        </div>
      </section>

      {/* Typography */}
      <section className="card stack">
        <h2 style={{ fontSize: 'var(--text-xl)', fontWeight: 700 }}>
          字号系统
        </h2>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-6)' }}>
          <div>
            <div style={{ fontSize: 'var(--text-xs)', color: 'var(--text-light)', marginBottom: '8px' }}>
              Hero (120px)
            </div>
            <div style={{ fontSize: 'var(--text-hero)', fontWeight: 800, lineHeight: 0.98 }}>
              会议智能
            </div>
          </div>
          <div>
            <div style={{ fontSize: 'var(--text-xs)', color: 'var(--text-light)', marginBottom: '8px' }}>
              XXL (56px)
            </div>
            <div style={{ fontSize: 'var(--text-xxl)', fontWeight: 700 }}>
              实时会议记录
            </div>
          </div>
          <div>
            <div style={{ fontSize: 'var(--text-xs)', color: 'var(--text-light)', marginBottom: '8px' }}>
              XL (40px)
            </div>
            <div style={{ fontSize: 'var(--text-xl)', fontWeight: 700 }}>
              智能分析
            </div>
          </div>
          <div>
            <div style={{ fontSize: 'var(--text-xs)', color: 'var(--text-light)', marginBottom: '8px' }}>
              Base (17px)
            </div>
            <div style={{ fontSize: 'var(--text-base)' }}>
              这是正文文字，使用 17px 字号，行高 1.6，适合长文本阅读。
            </div>
          </div>
        </div>
      </section>

      {/* Table Example */}
      <section>
        <h2 style={{ fontSize: 'var(--text-xl)', fontWeight: 700, marginBottom: 'var(--space-4)' }}>
          毛玻璃表格
        </h2>
        <table className="data-table">
          <thead>
            <tr>
              <th>组件</th>
              <th>模糊强度</th>
              <th>透明度</th>
              <th>圆角</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>Header</td>
              <td>blur(30px)</td>
              <td>75%</td>
              <td>0px</td>
            </tr>
            <tr>
              <td>Card</td>
              <td>blur(40px)</td>
              <td>70%</td>
              <td>24px</td>
            </tr>
            <tr>
              <td>Modal</td>
              <td>blur(80px)</td>
              <td>92%</td>
              <td>32px</td>
            </tr>
          </tbody>
        </table>
      </section>

      {/* Modal */}
      <GlassModal
        isOpen={isModalOpen}
        onClose={() => setIsModalOpen(false)}
        title="极致毛玻璃 Modal"
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
        <p>
          这个 Modal 使用 <strong>blur(80px)</strong> 极致毛玻璃效果，
          透明度 92%，32px 大圆角。
        </p>
        <p>
          进入动画使用 scale(0.95 → 1) + fade in，350ms cubic-bezier 缓动。
        </p>
        <p>
          关闭按钮 hover 时旋转 90 度。按 ESC 键或点击背景也可以关闭。
        </p>
      </GlassModal>
    </div>
  );
}
