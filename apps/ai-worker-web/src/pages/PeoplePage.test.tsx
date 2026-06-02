import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { PeoplePage } from "./PeoplePage";
import type { PersonDTO } from "@/shared/api/types";

const people = Array.from({ length: 60 }, (_, index) =>
  makePerson(index + 1, `成员 ${String(index + 1).padStart(2, "0")}`),
);
let searchResults: PersonDTO[] = people;

vi.mock("@/shared/api/endpoints", () => ({
  searchPersons: vi.fn(async () => searchResults),
  createPerson: vi.fn(async () => makePerson(99, "新成员", "new@example.com")),
}));

describe("PeoplePage", () => {
  beforeEach(async () => {
    vi.clearAllMocks();
    searchResults = people;
    const endpoints = await import("@/shared/api/endpoints");
    vi.mocked(endpoints.createPerson).mockResolvedValue(makePerson(99, "新成员", "new@example.com"));
  });

  it("uses the URL query as the Java person search source", async () => {
    const endpoints = await import("@/shared/api/endpoints");

    renderPeoplePage("/people?q=%E6%9D%8E");

    expect(screen.getByRole("textbox", { name: "搜索人员" })).toHaveValue("李");
    await waitFor(() => expect(endpoints.searchPersons).toHaveBeenCalledWith("李", expect.any(Object)));
    expect(screen.getByTestId("people-location")).toHaveTextContent("/people?q=%E6%9D%8E");
  });

  it("keeps search state in the URL and announces empty results", async () => {
    const endpoints = await import("@/shared/api/endpoints");
    searchResults = [];

    renderPeoplePage();
    fireEvent.change(screen.getByRole("textbox", { name: "搜索人员" }), { target: { value: "Nobody" } });

    await waitFor(() => expect(endpoints.searchPersons).toHaveBeenCalledWith("Nobody", expect.any(Object)));
    expect(screen.getByTestId("people-location")).toHaveTextContent("/people?q=Nobody");
    expect(await screen.findByText("没有找到人员")).toBeInTheDocument();
  });

  it("creates a person from the page and shows it in the people workbench", async () => {
    const endpoints = await import("@/shared/api/endpoints");
    searchResults = [];

    renderPeoplePage();
    fireEvent.click(screen.getByRole("button", { name: "新建人员" }));
    fireEvent.change(screen.getByLabelText("姓名"), { target: { value: "新成员" } });
    fireEvent.change(screen.getByLabelText("邮箱"), { target: { value: "new@example.com" } });
    fireEvent.click(screen.getByRole("button", { name: /^创建$/ }));

    await waitFor(() => expect(endpoints.createPerson).toHaveBeenCalledWith({
      displayName: "新成员",
      email: "new@example.com",
    }));
    expect(await screen.findByText("新成员")).toBeInTheDocument();
    expect(screen.getByText("new@example.com")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "为 新成员 录入声纹" })).toHaveAttribute("href", "/enrollment?personId=p99");
  });

  it("virtualizes long person result sets instead of rendering every row", async () => {
    renderPeoplePage();

    const virtualList = await screen.findByTestId("people-virtual-list");
    expect(virtualList).toHaveAttribute("aria-rowcount", "60");
    expect(screen.getByText("成员 01")).toBeInTheDocument();
    expect(screen.queryByText("成员 60")).not.toBeInTheDocument();
    expect(screen.getAllByRole("listitem").length).toBeLessThan(30);
  });
});

function renderPeoplePage(path = "/people") {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route
          path="/people"
          element={(
            <>
              <LocationProbe />
              <PeoplePage />
            </>
          )}
        />
      </Routes>
    </MemoryRouter>,
  );
}

function LocationProbe() {
  const location = useLocation();
  return <span data-testid="people-location">{location.pathname + location.search}</span>;
}

function makePerson(index: number, displayName: string, email = `person${index}@example.com`): PersonDTO {
  return {
    personId: `p${index}`,
    displayName,
    email,
    externalId: null,
    createdAt: "2026-06-02T00:00:00Z",
  };
}
