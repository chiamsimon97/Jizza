import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Trash2 } from 'lucide-react'
import { workflowsApi } from '@/api/workflows'
import { Button } from '@/components/ui/Button'
import { Dialog } from '@/components/ui/Dialog'

interface Props {
  workflowId: string
  name: string
  /** "workflow" on the Workflows page, "recording" on the Recordings page. */
  what?: 'workflow' | 'recording'
}

/**
 * A Delete button that asks first. Deleting cancels the workflow's unfinished runs and turns off
 * its triggers; past runs stay in the run history.
 */
export function DeleteWorkflowButton({ workflowId, name, what = 'workflow' }: Props) {
  const [asking, setAsking] = useState(false)
  const queryClient = useQueryClient()
  const remove = useMutation({
    mutationFn: () => workflowsApi.delete(workflowId),
    onSuccess: () => {
      setAsking(false)
      void queryClient.invalidateQueries({ queryKey: ['workflows'] })
    },
  })
  const close = () => { if (!remove.isPending) { setAsking(false); remove.reset() } }

  return (
    // Clicks here must not also open the workflow card behind the button.
    <span onClick={e => e.stopPropagation()} onKeyDown={e => e.stopPropagation()}>
      <Button size="sm" variant="ghost" aria-label={`Delete ${name}`} title={`Delete ${what}`}
        className="text-red-600 hover:bg-red-50 hover:text-red-700 dark:text-red-400 dark:hover:bg-red-950/40"
        onClick={() => setAsking(true)}>
        <Trash2 className="h-4 w-4" />Delete
      </Button>
      <Dialog open={asking} title={`Delete ${name}?`} onClose={close}>
        <p className="text-sm text-gray-600 dark:text-gray-300">
          The {what} is removed from your list and from Jizza chat. Runs still in progress are stopped,
          and its schedule and form trigger stop starting it. Past runs stay in the run history.
        </p>
        <p className="mt-2 text-sm text-gray-600 dark:text-gray-300">
          Other workflows that run it as a sub-workflow will fail at that step.
        </p>
        {remove.isError && (
          <p role="alert" className="mt-3 text-sm text-red-600 dark:text-red-400">
            Couldn't delete it: {remove.error instanceof Error ? remove.error.message : 'please try again.'}
          </p>
        )}
        <div className="mt-5 flex justify-end gap-2">
          <Button size="sm" variant="secondary" onClick={close} disabled={remove.isPending}>Cancel</Button>
          <Button size="sm" variant="danger" loading={remove.isPending} onClick={() => remove.mutate()}>
            Delete {what}
          </Button>
        </div>
      </Dialog>
    </span>
  )
}
