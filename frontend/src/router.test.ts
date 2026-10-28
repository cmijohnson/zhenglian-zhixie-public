import { describe, expect, it } from 'vitest'
import router from './router'

describe('application routes', () => {
  it('exposes the television leadership screen outside the sidebar layout', () => {
    const standalone = router.resolve('/leadership-screen')
    const workspace = router.resolve('/leadership-dashboard')

    expect(standalone.name).toBe('leadership-screen')
    expect(standalone.meta.teamData).toBe(true)
    expect(standalone.matched).toHaveLength(1)
    expect(workspace.matched).toHaveLength(2)
  })

  it('protects the standalone leadership screen settings as an administrator route', () => {
    const settings = router.resolve('/admin/leadership-screen')

    expect(settings.meta.admin).toBe(true)
    expect(settings.matched).toHaveLength(2)
  })
})
