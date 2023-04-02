package com.likeminds.feedsx.post.detail.view

import android.annotation.SuppressLint
import android.app.Activity
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.likeminds.feedsx.R
import com.likeminds.feedsx.branding.model.BrandingData
import com.likeminds.feedsx.databinding.FragmentPostDetailBinding
import com.likeminds.feedsx.delete.model.*
import com.likeminds.feedsx.delete.view.AdminDeleteDialogFragment
import com.likeminds.feedsx.delete.view.SelfDeleteDialogFragment
import com.likeminds.feedsx.likes.model.COMMENT
import com.likeminds.feedsx.likes.model.LikesScreenExtras
import com.likeminds.feedsx.likes.model.POST
import com.likeminds.feedsx.likes.view.LikesActivity
import com.likeminds.feedsx.overflowmenu.model.*
import com.likeminds.feedsx.post.detail.model.CommentsCountViewData
import com.likeminds.feedsx.post.detail.model.PostDetailExtras
import com.likeminds.feedsx.post.detail.view.PostDetailActivity.Companion.POST_DETAIL_EXTRAS
import com.likeminds.feedsx.post.detail.view.adapter.PostDetailAdapter
import com.likeminds.feedsx.post.detail.view.adapter.PostDetailAdapter.PostDetailAdapterListener
import com.likeminds.feedsx.post.detail.view.adapter.PostDetailReplyAdapter.PostDetailReplyAdapterListener
import com.likeminds.feedsx.post.detail.viewmodel.PostDetailViewModel
import com.likeminds.feedsx.post.viewmodel.PostViewModel
import com.likeminds.feedsx.posttypes.model.CommentViewData
import com.likeminds.feedsx.posttypes.model.PostViewData
import com.likeminds.feedsx.posttypes.model.UserViewData
import com.likeminds.feedsx.posttypes.view.adapter.PostAdapterListener
import com.likeminds.feedsx.report.model.*
import com.likeminds.feedsx.report.view.ReportActivity
import com.likeminds.feedsx.report.view.ReportSuccessDialog
import com.likeminds.feedsx.utils.EndlessRecyclerScrollListener
import com.likeminds.feedsx.utils.ViewDataConverter.convertCommentsCount
import com.likeminds.feedsx.utils.ViewUtils
import com.likeminds.feedsx.utils.ViewUtils.hide
import com.likeminds.feedsx.utils.ViewUtils.show
import com.likeminds.feedsx.utils.customview.BaseFragment
import com.likeminds.feedsx.utils.membertagging.model.MemberTaggingExtras
import com.likeminds.feedsx.utils.membertagging.util.MemberTaggingUtil
import com.likeminds.feedsx.utils.membertagging.util.MemberTaggingViewListener
import com.likeminds.feedsx.utils.membertagging.view.MemberTaggingView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.onEach

@AndroidEntryPoint
class PostDetailFragment :
    BaseFragment<FragmentPostDetailBinding>(),
    PostAdapterListener,
    PostDetailAdapterListener,
    PostDetailReplyAdapterListener,
    SelfDeleteDialogFragment.DeleteAlertDialogListener,
    AdminDeleteDialogFragment.DeleteDialogListener {

    private val viewModel: PostDetailViewModel by viewModels()

    private val postSharedViewModel: PostViewModel by activityViewModels()

    private lateinit var postDetailExtras: PostDetailExtras

    private lateinit var mPostDetailAdapter: PostDetailAdapter
    private lateinit var mSwipeRefreshLayout: SwipeRefreshLayout

    private var parentCommentIdToReply: String? = null

    private lateinit var memberTagging: MemberTaggingView

    private val commentsCountPosition = 1
    private val commentsStartPosition = 2

    companion object {
        const val REPLIES_THRESHOLD = 5
    }

    override fun getViewBinding(): FragmentPostDetailBinding {
        return FragmentPostDetailBinding.inflate(layoutInflater)
    }

    override fun setUpViews() {
        super.setUpViews()

        fetchPostData()
        initRecyclerView()
        initMemberTaggingView()
        initCommentEditText()
        initSwipeRefreshLayout()
        initListeners()
    }

    override fun observeData() {
        super.observeData()

        observeErrors()
        observePostData()
        observeCommentData()
        observeMembersTaggingList()
    }

    private fun observeCommentData() {
        // observes addCommentResponse LiveData
        viewModel.addCommentResponse.observe(viewLifecycleOwner) {
            // gets old [CommentsCountViewData] from adapter
            val oldCommentsCountViewData =
                (mPostDetailAdapter[commentsCountPosition] as CommentsCountViewData)

            // creates new [CommentsCountViewData] by adding to [commentsCount]
            val newCommentsCountViewData = oldCommentsCountViewData.toBuilder()
                .commentsCount(oldCommentsCountViewData.commentsCount + 1)
                .build()

            // updates [CommentsCountViewData]
            mPostDetailAdapter.update(commentsCountPosition, newCommentsCountViewData)

            // adds new comment to adapter
            mPostDetailAdapter.add(commentsStartPosition, it)
        }

        // observes addReplyResponse LiveData
        viewModel.addReplyResponse.observe(viewLifecycleOwner) { pair ->
            // [parentCommentId] for the reply
            val parentCommentId = pair.first

            // view data of comment with level-1
            val replyViewData = pair.second

            addReplyToAdapter(parentCommentId, replyViewData)
        }

        // observes deleteCommentResponse LiveData
        viewModel.deleteCommentResponse.observe(viewLifecycleOwner) { pair ->
            val commentId = pair.first
            val parentCommentId = pair.second
            if (parentCommentId == null) {
                // level-0 comment

                // gets old [CommentsCountViewData] from adapter
                val oldCommentsCountViewData =
                    (mPostDetailAdapter[commentsCountPosition] as CommentsCountViewData)

                // todo make a fun
                // creates new [CommentsCountViewData] by adding to [commentsCount]
                val newCommentsCountViewData = oldCommentsCountViewData.toBuilder()
                    .commentsCount(oldCommentsCountViewData.commentsCount - 1)
                    .build()

                // updates [CommentsCountViewData]
                mPostDetailAdapter.update(commentsCountPosition, newCommentsCountViewData)

                val indexToRemove = getIndexAndCommentFromAdapter(commentId).first
                mPostDetailAdapter.removeIndex(indexToRemove)
                ViewUtils.showShortToast(
                    requireContext(),
                    getString(R.string.comment_deleted)
                )
            } else {
                // level-1 comment
                removeDeletedReply(parentCommentId, commentId)
            }
        }

        viewModel.getCommentResponse.observe(viewLifecycleOwner) { pair ->
            //page in api send
            val page = pair.first

            //comment data
            val comment = pair.second

            updateComment(comment, page)
        }
    }


    private fun addReplyToAdapter(parentCommentId: String, reply: CommentViewData) {
        val parentComment = getIndexAndCommentFromAdapter(parentCommentId)
        val parentIndex = parentComment.first
        val parentCommentViewData = parentComment.second

        parentCommentViewData.replies.add(0, reply)

        val newCommentViewData = parentCommentViewData.toBuilder()
            .repliesCount(parentCommentViewData.repliesCount + 1)
            .build()

        mPostDetailAdapter.update(parentIndex, newCommentViewData)
    }

    @SuppressLint("NewApi")
    private fun removeDeletedReply(parentCommentId: String, replyId: String) {
        val parentComment = getIndexAndCommentFromAdapter(parentCommentId)
        val parentIndex = parentComment.first
        val parentCommentViewData = parentComment.second

        parentCommentViewData.replies.removeIf {
            it.id == replyId
        }

        val newCommentViewData = parentCommentViewData.toBuilder()
            .repliesCount(parentCommentViewData.repliesCount - 1)
            .build()

        mPostDetailAdapter.update(parentIndex, newCommentViewData)
    }

    private fun updateComment(comment: CommentViewData, page: Int) {
        val indexAndComment = getIndexAndCommentFromAdapter(comment.id)
        val index = indexAndComment.first
        val adapterComment = indexAndComment.second
        if (page == 1) {
            mPostDetailAdapter.update(index, comment)
            scrollToPositionWithOffset(index, 75)
        } else {
            if ((adapterComment.replies.size.mod(REPLIES_THRESHOLD) != 0)) {
                adapterComment.replies.removeLast()
            }
            comment.replies.addAll(
                0,
                adapterComment.replies
            )
            mPostDetailAdapter.update(index, comment)
            scrollToPositionWithOffset(index + 1, 100)
        }
    }

    private fun observePostData() {
        viewModel.postResponse.observe(viewLifecycleOwner) { pair ->
            //page in sent in api
            val page = pair.first

            // post data
            val post = pair.second

            // update the comments count
            updateCommentsCount(post.commentsCount)

            //if pull to refresh is called
            if (mSwipeRefreshLayout.isRefreshing) {
                setPostDataAndScrollToTop(post)
                mSwipeRefreshLayout.isRefreshing = false
            }

            //normal adding
            if (page == 1) {
                setPostDataAndScrollToTop(post)
            } else {
//                mPostDetailAdapter.addAll(post)
            }
        }

        // observes deletePostResponse LiveData
        postSharedViewModel.deletePostResponse.observe(viewLifecycleOwner) {
            // todo pass the data to feed
            ViewUtils.showShortToast(
                requireContext(),
                getString(R.string.post_deleted)
            )
            requireActivity().finish()
        }

        // observes pinPostResponse LiveData
        postSharedViewModel.pinPostResponse.observe(viewLifecycleOwner) { postId ->
            val post = mPostDetailAdapter[0] as PostViewData
            if (post.isPinned) {
                ViewUtils.showShortToast(requireContext(), getString(R.string.post_pinned_to_top))
            } else {
                ViewUtils.showShortToast(requireContext(), getString(R.string.post_unpinned))
            }
        }
    }

    private fun setPostDataAndScrollToTop(post: PostViewData) {
        mPostDetailAdapter.add(0, post)
        mPostDetailAdapter.add(1, convertCommentsCount(post.commentsCount))
        mPostDetailAdapter.addAll(post.replies.toList())
        binding.rvPostDetails.scrollToPosition(0)
    }

    private fun fetchPostData() {
        viewModel.getPost(postDetailExtras.postId, 1)
    }

    /**
     * Observes for member tagging list, This is a live observer which will update itself on addition of new members
     * [taggingData] contains first -> page called in api
     * second -> Community Members and Groups
     */
    private fun observeMembersTaggingList() {
        viewModel.taggingData.observe(viewLifecycleOwner) { result ->
            MemberTaggingUtil.setMembersInView(memberTagging, result)
        }
    }

    // observes error events
    private fun observeErrors() {
        viewModel.errorMessageEventFlow.onEach { response ->
            when (response) {
                is PostDetailViewModel.ErrorMessageEvent.GetTaggingList -> {
                    ViewUtils.showErrorMessageToast(requireContext(), response.errorMessage)
                }
                is PostDetailViewModel.ErrorMessageEvent.GetPost -> {
                    ViewUtils.showErrorMessageToast(requireContext(), response.errorMessage)
                }
                is PostDetailViewModel.ErrorMessageEvent.LikeComment -> {
                    val commentId = response.commentId

                    //get comment and index
                    val pair = getIndexAndCommentFromAdapter(commentId)
                    val comment = pair.second
                    val index = pair.first

                    //update comment view data
                    val updatedComment = comment.toBuilder()
                        .isLiked(false)
                        .fromCommentLiked(true)
                        .likesCount(comment.likesCount - 1)
                        .build()

                    //update recycler view
                    mPostDetailAdapter.update(index, updatedComment)

                    //show error message
                    val errorMessage = response.errorMessage
                    ViewUtils.showErrorMessageToast(requireContext(), errorMessage)
                }
                is PostDetailViewModel.ErrorMessageEvent.AddComment -> {
                    ViewUtils.showErrorMessageToast(requireContext(), response.errorMessage)
                }
                is PostDetailViewModel.ErrorMessageEvent.DeleteComment -> {
                    val errorMessage = response.errorMessage
                    ViewUtils.showErrorMessageToast(requireContext(), errorMessage)
                }
            }
        }

        postSharedViewModel.errorMessageEventFlow.onEach { response ->
            when (response) {
                is PostViewModel.ErrorMessageEvent.LikePost -> {
                    val postId = response.postId

                    //get post and index
                    val pair = getIndexAndPostFromAdapter(postId)
                    val post = pair.second
                    val index = pair.first

                    //update post view data
                    val updatedPost = post.toBuilder()
                        .isLiked(false)
                        .fromPostLiked(true)
                        .likesCount(post.likesCount - 1)
                        .build()

                    //update recycler view
                    mPostDetailAdapter.update(index, updatedPost)

                    //show error message
                    val errorMessage = response.errorMessage
                    ViewUtils.showErrorMessageToast(requireContext(), errorMessage)
                }
                is PostViewModel.ErrorMessageEvent.SavePost -> {
                    val postId = response.postId

                    //get post and index
                    val pair = getIndexAndPostFromAdapter(postId)
                    val post = pair.second
                    val index = pair.first

                    //update post view data
                    val updatedPost = post.toBuilder()
                        .isSaved(false)
                        .fromPostSaved(true)
                        .build()

                    //update recycler view
                    mPostDetailAdapter.update(index, updatedPost)

                    //show error message
                    val errorMessage = response.errorMessage
                    ViewUtils.showErrorMessageToast(requireContext(), errorMessage)
                }
                is PostViewModel.ErrorMessageEvent.DeletePost -> {
                    val errorMessage = response.errorMessage
                    ViewUtils.showErrorMessageToast(requireContext(), errorMessage)
                }
                is PostViewModel.ErrorMessageEvent.PinPost -> {
                    val postId = response.postId

                    //get post and index
                    val pair = getIndexAndPostFromAdapter(postId)
                    val post = pair.second
                    val index = pair.first

                    //update post view data
                    val updatedPost = post.toBuilder()
                        .isPinned(!post.isPinned)
                        .build()

                    //update recycler view
                    mPostDetailAdapter.update(index, updatedPost)

                    //show error message
                    val errorMessage = response.errorMessage
                    ViewUtils.showErrorMessageToast(requireContext(), errorMessage)
                }
            }
        }
    }

    /**
     * initializes the [memberTaggingView] with the edit text
     * also sets listener to the [memberTaggingView]
     */
    private fun initMemberTaggingView() {
        memberTagging = binding.memberTaggingView
        memberTagging.initialize(
            MemberTaggingExtras.Builder()
                .editText(binding.etComment)
                .maxHeightInPercentage(0.4f)
                .color(
                    BrandingData.currentAdvanced?.third
                        ?: ContextCompat.getColor(binding.root.context, R.color.pure_blue)
                )
                .build()
        )
        memberTagging.addListener(object : MemberTaggingViewListener {
            override fun callApi(page: Int, searchName: String) {
                viewModel.getMembersForTagging(page, searchName)
            }
        })
    }

    // initializes the post detail screen recycler view
    private fun initRecyclerView() {
        val linearLayoutManager = LinearLayoutManager(context)
        mPostDetailAdapter = PostDetailAdapter(this, this, this)
        binding.rvPostDetails.apply {
            layoutManager = linearLayoutManager
            adapter = mPostDetailAdapter
            if (itemAnimator is SimpleItemAnimator)
                (itemAnimator as SimpleItemAnimator).supportsChangeAnimations = false
            show()
        }

        attachScrollListener(
            binding.rvPostDetails,
            linearLayoutManager
        )
    }

    // initializes swipe refresh layout and sets refresh listener
    private fun initSwipeRefreshLayout() {
        mSwipeRefreshLayout = binding.swipeRefreshLayout
        mSwipeRefreshLayout.setColorSchemeColors(
            BrandingData.getButtonsColor(),
        )

        mSwipeRefreshLayout.setOnRefreshListener {
            mSwipeRefreshLayout.isRefreshing = true
            fetchRefreshedData()
        }
    }

    //TODO: Call api and refresh the post data
    private fun fetchRefreshedData() {
        //TODO: testing data
        mPostDetailAdapter.add(
            2,
            CommentViewData.Builder()
                .isLiked(false)
                .id("6")
                .user(
                    UserViewData.Builder()
                        .name("Sid")
                        .build()
                )
                .likesCount(140)
                .text("This is a test comment 6")
                .build()
        )
        mPostDetailAdapter.add(
            3,
            CommentViewData.Builder()
                .isLiked(false)
                .id("7")
                .user(
                    UserViewData.Builder()
                        .name("Siddharth")
                        .build()
                )
                .likesCount(100)
                .isLiked(true)
                .text("This is a test comment 7")
                .build()
        )
        mSwipeRefreshLayout.isRefreshing = false
    }

    // updates the comments count on toolbar
    private fun updateCommentsCount(commentsCount: Int) {
        (requireActivity() as PostDetailActivity).binding.tvToolbarSubTitle.text =
            this.resources.getQuantityString(
                R.plurals.comments_small,
                commentsCount,
                commentsCount
            )
    }

    // attach scroll listener for pagination for comments
    private fun attachScrollListener(
        recyclerView: RecyclerView,
        layoutManager: LinearLayoutManager
    ) {
        recyclerView.addOnScrollListener(object : EndlessRecyclerScrollListener(layoutManager) {
            override fun onLoadMore(currentPage: Int) {
                // TODO: add logic
            }
        })
    }

    // initializes comment edittext with TextWatcher and focuses the keyboard
    private fun initCommentEditText() {
        binding.apply {
            if (postDetailExtras.isEditTextFocused) etComment.focusAndShowKeyboard()

            etComment.doAfterTextChanged {
                if (it?.trim().isNullOrEmpty()) {
                    ivCommentSend.isClickable = false
                    ivCommentSend.setImageResource(R.drawable.ic_comment_send_disable)
                } else {
                    ivCommentSend.isClickable = true
                    ivCommentSend.setImageResource(R.drawable.ic_comment_send_enable)
                }
            }
        }
    }

    private fun initListeners() {
        binding.apply {
            ivCommentSend.setOnClickListener {
                val text = etComment.text
                val updatedText = memberTagging.replaceSelectedMembers(text).trim()
                val postId = postDetailExtras.postId
                if (parentCommentIdToReply != null) {
                    // input text is reply to a comment
                    val parentCommentId = parentCommentIdToReply ?: return@setOnClickListener
                    viewModel.replyComment(
                        postDetailExtras.postId,
                        parentCommentId,
                        updatedText
                    )
                    hideReplyingToView()
                } else {
                    // input text is a comment
                    viewModel.addComment(postId, updatedText)
                }
                etComment.text = null
            }

            ivCommentSend.isClickable = false
            ivCommentSend.setImageResource(R.drawable.ic_comment_send_disable)

            ivRemoveReplyingTo.setOnClickListener {
                hideReplyingToView()
            }
        }
    }

    private fun addCommentToAdapter() {

    }

    private fun addReplyToAdapter() {

    }

    private fun hideReplyingToView() {
        binding.apply {
            parentCommentIdToReply = null
            tvReplyingTo.hide()
            ivRemoveReplyingTo.hide()
        }
    }

    override fun receiveExtras() {
        // TODO: handle when opened from route
        super.receiveExtras()
        if (arguments == null || arguments?.containsKey(POST_DETAIL_EXTRAS) == false) {
            requireActivity().supportFragmentManager.popBackStack()
            return
        }
        postDetailExtras = arguments?.getParcelable(POST_DETAIL_EXTRAS)!!
    }

    // processes delete post request
    private fun deletePost(
        postId: String,
        creatorId: String
    ) {
        val deleteExtras = DeleteExtras.Builder()
            .postId(postId)
            .entityType(DELETE_TYPE_POST)
            .build()

        showDeleteDialog(creatorId, deleteExtras)
    }

    // processes delete comment request
    private fun deleteComment(
        postId: String,
        commentId: String,
        creatorId: String,
        parentCommentId: String? = null,
    ) {
        Log.d("PUI", "deleteComment: $parentCommentId")
        val deleteExtras = DeleteExtras.Builder()
            .postId(postId)
            .commentId(commentId)
            .entityType(DELETE_TYPE_COMMENT)
            .parentCommentId(parentCommentId)
            .build()

        showDeleteDialog(creatorId, deleteExtras)
    }

    private fun showDeleteDialog(creatorId: String, deleteExtras: DeleteExtras) {
        if (creatorId == postSharedViewModel.getUserUniqueId()) {
            // when user deletes their own entity
            SelfDeleteDialogFragment.showDialog(
                childFragmentManager,
                deleteExtras
            )
        } else {
            // when CM deletes other user's entity
            AdminDeleteDialogFragment.showDialog(
                childFragmentManager,
                deleteExtras
            )
        }
    }

    // Processes report action on entity
    private fun reportEntity(
        entityId: String,
        creatorId: String,
        @ReportType
        entityType: Int
    ) {
        //create extras for [ReportActivity]
        val reportExtras = ReportExtras.Builder()
            .entityId(entityId)
            .entityCreatorId(creatorId)
            .entityType(entityType)
            .build()

        //get Intent for [ReportActivity]
        val intent = ReportActivity.getIntent(requireContext(), reportExtras)

        //start [ReportActivity] and check for result
        reportPostLauncher.launch(intent)
    }

    // updates post view data when see more is clicked
    override fun updatePostSeenFullContent(position: Int, alreadySeenFullContent: Boolean) {
        val item = mPostDetailAdapter[position]
        if (item is PostViewData) {
            val newViewData = item.toBuilder()
                .alreadySeenFullContent(alreadySeenFullContent)
                .build()
            mPostDetailAdapter.update(position, newViewData)
        }
    }

    // updates post view data when see more is clicked
    override fun updateCommentSeenFullContent(position: Int, alreadySeenFullContent: Boolean) {
//        todo
        val item = mPostDetailAdapter[position]
        if (item is CommentViewData) {
            val newViewData = item.toBuilder()
                .alreadySeenFullContent(alreadySeenFullContent)
                .fromCommentLiked(false)
                .build()
            mPostDetailAdapter.update(position, newViewData)
        }
    }

    override fun likePost(position: Int) {
        //get item
        val item = mPostDetailAdapter[position]
        if (item is PostViewData) {
            //new like count
            val newLikesCount = if (item.isLiked) {
                item.likesCount - 1
            } else {
                item.likesCount + 1
            }

            //update post view data
            val newViewData = item.toBuilder()
                .fromPostLiked(true)
                .isLiked(!item.isLiked)
                .likesCount(newLikesCount)
                .build()

            //call api
            postSharedViewModel.likePost(newViewData.id)
            //update recycler
            mPostDetailAdapter.update(position, newViewData)
        }
    }

    override fun savePost(position: Int) {
        //get item
        val item = mPostDetailAdapter[position]
        if (item is PostViewData) {
            //update the post view data
            val newViewData = item.toBuilder()
                .fromPostSaved(true)
                .isSaved(!item.isSaved)
                .build()

            //call api
            postSharedViewModel.savePost(newViewData.id)

            //update recycler
            mPostDetailAdapter.update(position, newViewData)
        }
    }

    // callback when add comment is clicked on post
    override fun comment(postId: String) {
        binding.etComment.focusAndShowKeyboard()
    }

    // callback when +x more text is clicked to see more documents
    override fun onMultipleDocumentsExpanded(postData: PostViewData, position: Int) {
        if (position == mPostDetailAdapter.items().size - 1) {
            binding.rvPostDetails.post {
                scrollToPositionWithOffset(position, 75)
            }
        }

        mPostDetailAdapter.update(
            position,
            postData.toBuilder().isExpanded(true).build()
        )
    }

    //get index and post from the adapter using postId
    private fun getIndexAndPostFromAdapter(postId: String): Pair<Int, PostViewData> {
        val index = mPostDetailAdapter.items().indexOfFirst {
            (it is PostViewData) && (it.id == postId)
        }

        val post = getPostFromAdapter(index)

        return Pair(index, post)
    }

    //get post from the adapter using index
    private fun getPostFromAdapter(position: Int): PostViewData {
        return mPostDetailAdapter.items()[position] as PostViewData
    }

    /**
     * Scroll to a position with offset from the top header
     * @param position Index of the item to scroll to
     * @param offset value with which to scroll
     */
    private fun scrollToPositionWithOffset(position: Int, offset: Int) {
        val px = (ViewUtils.dpToPx(offset) * 1.5).toInt()
        (binding.rvPostDetails.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(
            position,
            px
        )
    }

    // callback when likes count of post is clicked - opens likes screen
    override fun showLikesScreen(postId: String) {
        val likesScreenExtras = LikesScreenExtras.Builder()
            .postId(postId)
            .entityType(POST)
            .build()
        LikesActivity.start(requireContext(), likesScreenExtras)
    }

    // callback when comment/reply is liked
    override fun likeComment(commentId: String) {
        val indexAndComment = getIndexAndCommentFromAdapter(commentId)
        val position = indexAndComment.first
        val comment = indexAndComment.second
        //new like count
        val newLikesCount = if (comment.isLiked) {
            comment.likesCount - 1
        } else {
            comment.likesCount + 1
        }

        //update comment view data
        val newViewData = comment.toBuilder()
            .fromCommentLiked(true)
            .isLiked(!comment.isLiked)
            .likesCount(newLikesCount)
            .build()

        //call api
        viewModel.likeComment(newViewData.postId, newViewData.id)
        //update recycler
        mPostDetailAdapter.update(position, newViewData)
    }

    override fun likeReply(parentCommentId: String, replyId: String) {
        val parentIndexAndComment = getIndexAndCommentFromAdapter(parentCommentId)
        val position = parentIndexAndComment.first
        val parentComment = parentIndexAndComment.second

        val reply = getIndexAndReplyFromComment(parentComment, replyId)
        val replyIndex = reply.first
        val replyViewData = reply.second

        //new like count
        val newLikesCount = if (replyViewData.isLiked) {
            replyViewData.likesCount - 1
        } else {
            replyViewData.likesCount + 1
        }

        val updatedReply = parentComment.replies[replyIndex]
            .toBuilder()
            .isLiked(!replyViewData.isLiked)
            .likesCount(newLikesCount)
            .build()

        parentComment.replies[replyIndex] = updatedReply

        //update comment view data
        val newViewData = parentComment.toBuilder()
            .replies(parentComment.replies)
            .build()

        //call api
        viewModel.likeComment(newViewData.postId, updatedReply.id)
        //update recycler
        mPostDetailAdapter.update(position, newViewData)
    }

    private fun getIndexAndReplyFromComment(
        parentComment: CommentViewData,
        replyId: String
    ): Pair<Int, CommentViewData> {
        val index = parentComment.replies.indexOfFirst {
            it.id == replyId
        }

        val reply = parentComment.replies[index]

        return Pair(index, reply)
    }

    //get index and post from the adapter using postId
    private fun getIndexAndCommentFromAdapter(commentId: String): Pair<Int, CommentViewData> {
        val index = mPostDetailAdapter.items().indexOfFirst {
            (it is CommentViewData) && (it.id == commentId)
        }
        Log.d("PUI", "getIndexAndCommentFromAdapter: $index")

        val comment = getCommentFromAdapter(index)

        return Pair(index, comment)
    }

    private fun getCommentFromAdapter(position: Int): CommentViewData {
        return mPostDetailAdapter.items()[position] as CommentViewData
    }

    override fun fetchReplies(commentId: String) {
        val indexAndComment = getIndexAndCommentFromAdapter(commentId)
        val index = indexAndComment.first
        val comment = indexAndComment.second
        Log.d("PUI", "fetchReplies: $index")

        viewModel.getComment(
            comment.postId,
            comment.id,
            1
        )

//        if (mPostDetailAdapter[index] is CommentViewData) {
//            val comment = mPostDetailAdapter[index] as CommentViewData
//            comment.replies.addAll(
//                mutableListOf(
//                    CommentViewData.Builder()
//                        .isLiked(false)
//                        .id("6")
//                        .user(
//                            UserViewData.Builder()
//                                .name("Natesh Rehlan")
//                                .build()
//                        )
//                        .level(1)
//                        .text("This is a test reply 1")
//                        .build()
//                )
//            )
//            mPostDetailAdapter.update(index, comment)
//            binding.rvPostDetails.smoothScrollToPosition(
//                mPostDetailAdapter.itemCount
//            )
//        }
    }

    // callback when replying on a comment
    override fun replyOnComment(
        commentId: String,
        commentPosition: Int,
        parentCommenter: UserViewData
    ) {
        parentCommentIdToReply = commentId
        binding.apply {
            tvReplyingTo.show()
            ivRemoveReplyingTo.show()

            tvReplyingTo.text = String.format(
                getString(R.string.replying_to_s),
                parentCommenter.name
            )

            etComment.focusAndShowKeyboard()

            rvPostDetails.smoothScrollToPosition(
                commentPosition
            )
        }
    }

    override fun onPostMenuItemClicked(
        postId: String,
        creatorId: String,
        title: String
    ) {
        when (title) {
            DELETE_POST_MENU_ITEM -> {
                deletePost(
                    postId,
                    creatorId
                )
            }
            REPORT_POST_MENU_ITEM -> {
                reportEntity(postId, creatorId, REPORT_TYPE_POST)
            }
            PIN_POST_MENU_ITEM -> {
                pinPost(postId)
            }
            UNPIN_POST_MENU_ITEM -> {
                unpinPost(postId)
            }
        }
    }

    private fun pinPost(postId: String) {
        //get item
        val index = 0
        val post = mPostDetailAdapter[0] as PostViewData

        //get pin menu item
        val menuItems = post.menuItems.toMutableList()
        val pinPostIndex = menuItems.indexOfFirst {
            (it.title == PIN_POST_MENU_ITEM)
        }

        //if pin item doesn't exist
        if (pinPostIndex == -1) return

        //update pin menu item
        val pinPostMenuItem = menuItems[pinPostIndex]
        val newPinPostMenuItem = pinPostMenuItem.toBuilder().title(UNPIN_POST_MENU_ITEM).build()
        menuItems[pinPostIndex] = newPinPostMenuItem

        //update the post view data
        val newViewData = post.toBuilder()
            .isPinned(!post.isPinned)
            .menuItems(menuItems)
            .build()

        //call api
        postSharedViewModel.pinPost(postId)

        //update recycler
        mPostDetailAdapter.update(index, newViewData)
    }

    private fun unpinPost(postId: String) {
        //get item
        val index = 0
        val post = mPostDetailAdapter[index] as PostViewData

        //get unpin menu item
        val menuItems = post.menuItems.toMutableList()
        val unPinPostIndex = menuItems.indexOfFirst {
            (it.title == UNPIN_POST_MENU_ITEM)
        }

        //if unpin item doesn't exist
        if (unPinPostIndex == -1) return

        //update unpin menu item
        val unPinPostMenuItem = menuItems[unPinPostIndex]
        val newUnPinPostMenuItem =
            unPinPostMenuItem.toBuilder().title(PIN_POST_MENU_ITEM).build()
        menuItems[unPinPostIndex] = newUnPinPostMenuItem

        //update the post view data
        val newViewData = post.toBuilder()
            .isPinned(!post.isPinned)
            .menuItems(menuItems)
            .build()

        //call api
        postSharedViewModel.pinPost(postId)

        //update recycler
        mPostDetailAdapter.update(index, newViewData)
    }

    // callback for comment's menu is item
    override fun onCommentMenuItemClicked(
        postId: String,
        commentId: String,
        creatorId: String,
        title: String
    ) {
        when (title) {
            DELETE_COMMENT_MENU_ITEM -> {
                deleteComment(
                    postId,
                    commentId,
                    creatorId
                )
            }
            REPORT_COMMENT_MENU_ITEM -> {
                reportEntity(
                    commentId,
                    creatorId,
                    REPORT_TYPE_COMMENT
                )
            }
        }
    }

    // callback when view more replies is clicked
    override fun viewMoreReplies(
        parentCommentId: String,
        page: Int
    ) {
        val comment = getIndexAndCommentFromAdapter(parentCommentId).second
        viewModel.getComment(
            comment.postId,
            parentCommentId,
            page
        )
    }

    // callback when the item of reply menu is clicked
    override fun onReplyMenuItemClicked(
        postId: String,
        parentCommentId: String,
        replyId: String,
        creatorId: String,
        title: String
    ) {
        when (title) {
            DELETE_COMMENT_MENU_ITEM -> {
                deleteComment(
                    postId,
                    replyId,
                    creatorId,
                    parentCommentId
                )
            }
            REPORT_COMMENT_MENU_ITEM -> {
                reportEntity(
                    replyId,
                    creatorId,
                    REPORT_TYPE_REPLY
                )
            }
        }
    }

    private val reportPostLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                ReportSuccessDialog("Message").show(
                    childFragmentManager,
                    ReportSuccessDialog.TAG
                )
            }
        }

    // callback when self post is deleted by user
    override fun selfDelete(deleteExtras: DeleteExtras) {
        when (deleteExtras.entityType) {
            DELETE_TYPE_POST -> {
                postSharedViewModel.deletePost(deleteExtras.postId)
            }
            DELETE_TYPE_COMMENT -> {
                val commentId = deleteExtras.commentId ?: return
                viewModel.deleteComment(
                    deleteExtras.postId,
                    commentId,
                    parentCommentId = deleteExtras.parentCommentId
                )
            }
        }
    }

    // callback when other's post is deleted by CM
    override fun adminDelete(deleteExtras: DeleteExtras, reason: String) {
        when (deleteExtras.entityType) {
            DELETE_TYPE_POST -> {
                postSharedViewModel.deletePost(deleteExtras.postId, reason)
            }
            DELETE_TYPE_COMMENT -> {
                val commentId = deleteExtras.commentId ?: return
                viewModel.deleteComment(
                    deleteExtras.postId,
                    commentId,
                    parentCommentId = deleteExtras.parentCommentId,
                    reason = reason
                )
            }
        }
    }

    // callback when likes count of a comment is clicked - opens likes screen
    override fun showLikesScreen(postId: String, commentId: String) {
        val likesScreenExtras = LikesScreenExtras.Builder()
            .postId(postId)
            .commentId(commentId)
            .entityType(COMMENT)
            .build()
        LikesActivity.start(requireContext(), likesScreenExtras)
    }
}