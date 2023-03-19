package com.likeminds.feedsx.post.detail.view.adapter

import com.likeminds.feedsx.post.detail.view.adapter.databinder.ItemPostDetailReplyViewDataBinder
import com.likeminds.feedsx.post.detail.view.adapter.databinder.ItemReplyViewMoreReplyViewDataBinder
import com.likeminds.feedsx.utils.customview.BaseRecyclerAdapter
import com.likeminds.feedsx.utils.customview.ViewDataBinder
import com.likeminds.feedsx.utils.model.BaseViewType

class PostDetailReplyAdapter constructor(
    val postDetailAdapterListener: PostDetailAdapter.PostDetailAdapterListener,
    val postDetailReplyAdapterListener: PostDetailReplyAdapterListener
) : BaseRecyclerAdapter<BaseViewType>() {

    init {
        initViewDataBinders()
    }

    override fun getSupportedViewDataBinder(): MutableList<ViewDataBinder<*, *>> {
        val viewDataBinders = ArrayList<ViewDataBinder<*, *>>(1)

        val itemPostDetailReplyViewDataBinder = ItemPostDetailReplyViewDataBinder(
            postDetailAdapterListener,
            postDetailReplyAdapterListener
        )
        viewDataBinders.add(itemPostDetailReplyViewDataBinder)

        val itemReplyViewMoreReplyViewDataBinder =
            ItemReplyViewMoreReplyViewDataBinder(postDetailReplyAdapterListener)
        viewDataBinders.add(itemReplyViewMoreReplyViewDataBinder)

        return viewDataBinders
    }

    interface PostDetailReplyAdapterListener {

        fun viewMoreReplies(
            parentCommentId: String,
            parentCommentPosition: Int,
            currentVisibleReplies: Int
        )

        fun onReplyMenuItemClicked(
            postId: String,
            replyId: String,
            title: String,
            creatorId: String
        ) {}
    }
}