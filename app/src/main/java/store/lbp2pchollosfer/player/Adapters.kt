package store.lbp2pchollosfer.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load

class CatAdapter(
    private val onPick: (CatItem) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var items: List<CatItem> = emptyList()
    var selected: Int = -1

    private class Holder(v: View) : RecyclerView.ViewHolder(v)

    override fun getItemViewType(position: Int) = if (items[position].header) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layout = if (viewType == 0) R.layout.item_header else R.layout.item_category
        return Holder(LayoutInflater.from(parent.context).inflate(layout, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        val tv = holder.itemView as TextView
        if (item.header) {
            tv.text = item.title
            return
        }
        tv.text = "${item.title}  ·  ${item.count}"
        tv.isSelected = position == selected
        tv.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
            val old = selected
            selected = pos
            if (old >= 0) notifyItemChanged(old)
            notifyItemChanged(pos)
            onPick(items[pos])
        }
    }

    override fun getItemCount() = items.size
}

class ChAdapter(
    private val items: List<Channel>,
    private val onPick: (Int) -> Unit,
) : RecyclerView.Adapter<ChAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val logo: ImageView = v.findViewById(R.id.logo)
        val name: TextView = v.findViewById(R.id.name)
        val group: TextView = v.findViewById(R.id.group)
        val progress: ProgressBar = v.findViewById(R.id.progress)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = items[position]
        val ctx = holder.itemView.context
        holder.name.text = ch.name

        if (ch.logo.isNotEmpty()) {
            holder.logo.load(ch.logo)
        } else {
            holder.logo.dispose()
            holder.logo.setImageDrawable(null)
        }

        if (ch.nowTitle != null) {
            holder.group.text = "▶ " + ch.nowTitle
            holder.group.setTextColor(ContextCompat.getColor(ctx, R.color.accent))
            val span = ch.nowEndMs - ch.nowStartMs
            if (span > 0) {
                val pct = ((System.currentTimeMillis() - ch.nowStartMs) * 100 / span).toInt()
                holder.progress.progress = pct.coerceIn(0, 100)
                holder.progress.visibility = View.VISIBLE
            } else {
                holder.progress.visibility = View.GONE
            }
        } else {
            holder.group.text = ch.group
            holder.group.setTextColor(ContextCompat.getColor(ctx, R.color.muted))
            holder.progress.visibility = View.GONE
        }

        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onPick(pos)
        }
    }

    override fun getItemCount() = items.size
}
