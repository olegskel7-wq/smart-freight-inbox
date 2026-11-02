import type { Page } from '../../types.ts'

/** Previous / Next under the list; hidden when everything fits on one page. */
export default function Pager({ data, page, onPage }: {
  data: Page<unknown>
  /** The page the URL asks for — Previous / Next step from it. */
  page: number
  onPage: (page: number) => void
}) {
  if (data.totalPages <= 1) return null
  return (
    <nav className="d-flex justify-content-between align-items-center mt-3" aria-label="Pages">
      <span className="text-muted small">
        Page {data.page + 1} of {data.totalPages} · {data.totalElements} tickets
      </span>
      <div className="btn-group">
        <button
          type="button"
          className="btn btn-outline-secondary btn-sm"
          disabled={data.page <= 0}
          onClick={() => onPage(page - 1)}
        >
          Previous
        </button>
        <button
          type="button"
          className="btn btn-outline-secondary btn-sm"
          disabled={data.page >= data.totalPages - 1}
          onClick={() => onPage(page + 1)}
        >
          Next
        </button>
      </div>
    </nav>
  )
}
