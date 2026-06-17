import { HeroSection } from '@shared/components/HeroSection';

/**
 * 12列不规则网格展示页面
 */
export function GridShowcasePage() {
  return (
    <div className="page page--workbench">
      <HeroSection
        label="GRID SYSTEM"
        title="12 列不规则网格"
        subtitle="灵活的网格系统，支持 span 1-12 任意组合"
      />

      {/* 示例 1: 6 + 6 */}
      <section>
        <h2 style={{ fontSize: 'var(--text-lg)', marginBottom: 'var(--space-4)' }}>
          布局示例：6 + 6（对称）
        </h2>
        <div className="grid-12">
          <div className="grid-item span-6">
            <h3>左侧内容区</h3>
            <p>占据 6 列（50% 宽度）。适合展示主要内容、图文并茂的介绍。</p>
          </div>
          <div className="grid-item span-6">
            <h3>右侧内容区</h3>
            <p>占据 6 列（50% 宽度）。与左侧平衡，形成对称布局。</p>
          </div>
        </div>
      </section>

      {/* 示例 2: 8 + 4 */}
      <section>
        <h2 style={{ fontSize: 'var(--text-lg)', marginBottom: 'var(--space-4)' }}>
          布局示例：8 + 4（主次）
        </h2>
        <div className="grid-12">
          <div className="grid-item span-8">
            <h3>主要内容区域</h3>
            <p>占据 8 列（约 67% 宽度）。适合展示详细内容、长文本、数据表格。信息重要性决定尺寸，不是为了不对称而不对称。</p>
          </div>
          <div className="grid-item span-4">
            <h3>侧边栏</h3>
            <p>占据 4 列（约 33% 宽度）。适合 Stats、导航、相关链接。</p>
          </div>
        </div>
      </section>

      {/* 示例 3: 4 + 4 + 4 */}
      <section>
        <h2 style={{ fontSize: 'var(--text-lg)', marginBottom: 'var(--space-4)' }}>
          布局示例：4 + 4 + 4（三栏）
        </h2>
        <div className="grid-12">
          <div className="grid-item span-4">
            <h3>功能卡片 1</h3>
            <p>占据 4 列。三栏布局，每栏约 33% 宽度，适合展示并列功能。</p>
          </div>
          <div className="grid-item span-4">
            <h3>功能卡片 2</h3>
            <p>占据 4 列。视觉平衡，信息清晰。</p>
          </div>
          <div className="grid-item span-4">
            <h3>功能卡片 3</h3>
            <p>占据 4 列。完整的三栏对称布局。</p>
          </div>
        </div>
      </section>

      {/* 示例 4: 混合布局 */}
      <section>
        <h2 style={{ fontSize: 'var(--text-lg)', marginBottom: 'var(--space-4)' }}>
          布局示例：混合（12 → 6+6 → 4+4+4）
        </h2>
        <div className="grid-12">
          <div className="grid-item span-12">
            <h3>全宽横幅</h3>
            <p>占据 12 列（100% 宽度）。适合重要通知、Hero 区域、大图展示。</p>
          </div>
          <div className="grid-item span-6">
            <h3>中等卡片 A</h3>
            <p>占据 6 列（50%）。</p>
          </div>
          <div className="grid-item span-6">
            <h3>中等卡片 B</h3>
            <p>占据 6 列（50%）。</p>
          </div>
          <div className="grid-item span-4">
            <h3>小卡片 1</h3>
            <p>占据 4 列（33%）。</p>
          </div>
          <div className="grid-item span-4">
            <h3>小卡片 2</h3>
            <p>占据 4 列（33%）。</p>
          </div>
          <div className="grid-item span-4">
            <h3>小卡片 3</h3>
            <p>占据 4 列（33%）。</p>
          </div>
        </div>
      </section>

      {/* 示例 5: 不规则组合 */}
      <section>
        <h2 style={{ fontSize: 'var(--text-lg)', marginBottom: 'var(--space-4)' }}>
          布局示例：不规则（3+9 → 5+7）
        </h2>
        <div className="grid-12">
          <div className="grid-item span-3">
            <h3>导航</h3>
            <p>25% 宽度</p>
          </div>
          <div className="grid-item span-9">
            <h3>内容区域</h3>
            <p>75% 宽度。不对称但有功能理由：左侧是紧凑导航，右侧是主要内容。</p>
          </div>
          <div className="grid-item span-5">
            <h3>信息卡片</h3>
            <p>约 42% 宽度</p>
          </div>
          <div className="grid-item span-7">
            <h3>详情区域</h3>
            <p>约 58% 宽度。5+7 的不对称组合。</p>
          </div>
        </div>
      </section>

      {/* 响应式说明 */}
      <section className="card">
        <h2 style={{ fontSize: 'var(--text-xl)', marginBottom: 'var(--space-4)' }}>
          响应式断点
        </h2>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-4)' }}>
          <div>
            <strong>桌面（&gt; 1024px）</strong>
            <p style={{ color: 'var(--text-light)', margin: 0 }}>
              完整 12 列网格，所有 span 值生效
            </p>
          </div>
          <div>
            <strong>平板（768-1024px）</strong>
            <p style={{ color: 'var(--text-light)', margin: 0 }}>
              8 列网格，span-6 → span-4，span-8 → span-8，span-4 → span-4
            </p>
          </div>
          <div>
            <strong>移动端（&lt; 768px）</strong>
            <p style={{ color: 'var(--text-light)', margin: 0 }}>
              单列布局，所有网格项堆叠显示
            </p>
          </div>
        </div>
      </section>
    </div>
  );
}
