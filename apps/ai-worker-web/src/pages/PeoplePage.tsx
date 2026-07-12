import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { createPerson, searchPersons } from "@/shared/api/endpoints";
import { PersonCreateModal } from "@/shared/components/PersonCreateModal";
import { useDebouncedSearch } from "@/shared/hooks/useDebouncedSearch";
import { VirtualList } from "@/shared/list/VirtualList";
import type { PersonDTO } from "@/shared/api/types";
import { formatError } from "@/shared/utils/format-error";

const VIRTUAL_LIST_THRESHOLD = 50;
const PERSON_ROW_HEIGHT = 72;
const PERSON_LIST_HEIGHT = 432;

export function PeoplePage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const queryFromUrl = searchParams.get("q") ?? "";
  const [query, setQuery] = useState(queryFromUrl);
  const [createOpen, setCreateOpen] = useState(false);
  const [createdPeople, setCreatedPeople] = useState<PersonDTO[]>([]);

  const fetchPeople = useCallback((q: string, signal: AbortSignal) => searchPersons(q, { signal }), []);
  const peopleSearch = useDebouncedSearch<PersonDTO>(fetchPeople);
  const { search } = peopleSearch;

  useEffect(() => {
    if (queryFromUrl !== query) setQuery(queryFromUrl);
  }, [query, queryFromUrl]);

  useEffect(() => {
    search(query);
  }, [query, search]);

  const people = useMemo(
    () => mergePeople(createdPeople, peopleSearch.results ?? []),
    [createdPeople, peopleSearch.results],
  );
  const error = peopleSearch.error ? formatError(peopleSearch.error) : null;

  const updateQuery = (nextQuery: string) => {
    setQuery(nextQuery);
    const trimmed = nextQuery.trim();
    setSearchParams(trimmed ? { q: nextQuery } : {}, { replace: true });
  };

  const handleCreated = (person: PersonDTO) => {
    setCreatedPeople((current) => mergePeople([person], current));
    setCreateOpen(false);
  };

  return (
    <div className="stack">
      <header className="page-header">
        <div>
          <h1 className="page-title">人员</h1>
          <p className="page-subtitle">{people.length} 个匹配人员</p>
        </div>
        <div className="toolbar">
          <Link className="button button--secondary" to="/enrollment">声纹录入</Link>
          <button className="button button--primary" type="button" onClick={() => setCreateOpen(true)}>
            新建人员
          </button>
        </div>
      </header>

      <section className="card stack" aria-labelledby="people-search-title">
        <div className="page-header">
          <div>
            <h2 id="people-search-title">人员列表</h2>
            <p className="page-subtitle" role="status" aria-live="polite">
              {peopleSearch.loading ? "搜索中…" : `${people.length} 个结果`}
            </p>
          </div>
        </div>

        <div className="field">
          <label className="field__label" htmlFor="people-search">搜索人员</label>
          <input
            id="people-search"
            name="personSearch"
            className="input"
            value={query}
            onChange={(event) => updateQuery(event.target.value)}
            placeholder="按姓名 / 邮箱搜索…"
            autoComplete="off"
            spellCheck={false}
          />
        </div>

        {error ? (
          <div className="banner banner--danger" role="alert">
            <strong className="banner__title">人员加载失败</strong>
            <span className="banner__body">{error}</span>
          </div>
        ) : null}

        {!peopleSearch.loading && !error && people.length === 0 ? (
          <div className="empty-state">
            <strong>没有找到人员</strong>
            <span>可以新建人员后继续会议或声纹录入。</span>
          </div>
        ) : null}

        {people.length > VIRTUAL_LIST_THRESHOLD ? (
          <VirtualList
            items={people}
            rowHeight={PERSON_ROW_HEIGHT}
            height={PERSON_LIST_HEIGHT}
            overscan={4}
            keyOf={(person) => person.personId}
            renderRow={(person) => <PersonRow person={person} />}
            testId="people-virtual-list"
          />
        ) : (
          <div className="people-list" role="list" aria-label="人员">
            {people.map((person) => (
              <div key={person.personId} role="listitem">
                <PersonRow person={person} />
              </div>
            ))}
          </div>
        )}
      </section>

      <PersonCreateModal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onCreated={handleCreated}
        createFn={createPerson}
      />
    </div>
  );
}

function PersonRow({ person }: { person: PersonDTO }) {
  return (
    <div className="people-row">
      <div className="people-row__main">
        <strong className="people-row__name">{person.displayName}</strong>
        <span className="people-row__meta" translate="no">
          {person.email ?? "未设置邮箱"}
        </span>
      </div>
      <div className="toolbar">
        <Link className="button button--ghost" to={`/speaker-profiles?personId=${encodeURIComponent(person.personId)}`}>
          查看 {person.displayName} 声纹档案
        </Link>
        <Link className="button button--secondary" to={`/enrollment?personId=${encodeURIComponent(person.personId)}`}>
          为 {person.displayName} 录入声纹
        </Link>
      </div>
    </div>
  );
}

function mergePeople(primary: PersonDTO[], secondary: PersonDTO[]): PersonDTO[] {
  const seen = new Set<string>();
  const merged: PersonDTO[] = [];
  for (const person of [...primary, ...secondary]) {
    if (!person.personId || seen.has(person.personId)) continue;
    seen.add(person.personId);
    merged.push(person);
  }
  return merged;
}

