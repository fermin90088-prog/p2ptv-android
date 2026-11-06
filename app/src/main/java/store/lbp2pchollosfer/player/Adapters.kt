package store.lbp2pchollosfer.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

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
        val name: TextView = v.findViewById(R.id.name)
        val group: TextView = v.findViewById(R.id.group)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = items[position]
        holder.name.text = ch.name
        holder.group.text = ch.group
        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onPick(pos)
        }
    }

    override fun getItemCount() = items.size
}
