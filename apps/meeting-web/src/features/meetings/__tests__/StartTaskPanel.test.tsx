import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { TestRouter } from "@shared/test/TestRouter";
import { StartTaskPanel } from "../StartTaskPanel";

describe("StartTaskPanel", () => {
  it("routes users to direct audio upload processing instead of asking for a file id", () => {
    render(
      <TestRouter>
        <StartTaskPanel meetingId="mtg_01" />
      </TestRouter>,
    );

    expect(screen.queryByLabelText("音频源文件编号")).not.toBeInTheDocument();
    expect(screen.queryByPlaceholderText("例：后台上传后生成的音频文件编号")).not.toBeInTheDocument();
    expect(screen.getByText("无需手动填写文件编号")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "选择音频并处理" })).toHaveAttribute(
      "href",
      "/meetings/mtg_01/audio",
    );
  });
});
