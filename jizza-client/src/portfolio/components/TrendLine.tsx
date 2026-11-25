import { LineChart } from '@/components/charts/LineChart'
import { formatINRShort } from '@/lib/money'

/** A figure month by month (net worth, score), with the table view every chart has. */
export function TrendLine({ title, description, points, format = formatINRShort }: {
  title: string
  description?: string
  points: { label: string; value: number }[]
  format?: (n: number) => string
}) {
  return (
    <LineChart title={title} description={description} labels={points.map(p => p.label)}
      series={[{ name: title, values: points.map(p => p.value) }]} format={format} />
  )
}
