package com.likeminds.feedsx.feed.viewmodel

import android.annotation.SuppressLint
import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkContinuation
import androidx.work.WorkManager
import com.likeminds.feedsx.feed.UserRepository
import com.likeminds.feedsx.post.PostRepository
import com.likeminds.feedsx.post.create.util.PostAttachmentUploadWorker
import com.likeminds.feedsx.posttypes.model.PostViewData
import com.likeminds.feedsx.posttypes.model.UserViewData
import com.likeminds.feedsx.utils.UserPreferences
import com.likeminds.feedsx.utils.ViewDataConverter
import com.likeminds.feedsx.utils.ViewDataConverter.convertPost
import com.likeminds.feedsx.utils.ViewDataConverter.createAttachments
import com.likeminds.feedsx.utils.coroutine.launchIO
import com.likeminds.likemindsfeed.LMFeedClient
import com.likeminds.likemindsfeed.helper.model.RegisterDeviceRequest
import com.likeminds.likemindsfeed.initiateUser.model.InitiateUserRequest
import com.likeminds.likemindsfeed.post.model.*
import com.likeminds.likemindsfeed.sdk.model.User
import com.likeminds.likemindsfeed.universalfeed.model.GetFeedRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject

@HiltViewModel
class FeedViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val postRepository: PostRepository,
    private val userPreferences: UserPreferences
) : ViewModel() {

    private val lmFeedClient = LMFeedClient.getInstance()

    private var temporaryPostId: Long? = null

    private val _userResponse = MutableLiveData<UserViewData>()
    val userResponse: LiveData<UserViewData> = _userResponse

    private val _logoutResponse = MutableLiveData<Boolean>()
    val logoutResponse: LiveData<Boolean> = _logoutResponse

    private val _universalFeedResponse = MutableLiveData<Pair<Int, List<PostViewData>>>()
    val universalFeedResponse: LiveData<Pair<Int, List<PostViewData>>> = _universalFeedResponse

    private val _deletePostResponse = MutableLiveData<String>()
    val deletePostResponse: LiveData<String> = _deletePostResponse

    private val _pinPostResponse = MutableLiveData<String>()
    val pinPostResponse: LiveData<String> = _pinPostResponse

    private val errorMessageChannel = Channel<ErrorMessageEvent>(Channel.BUFFERED)
    val errorMessageEventFlow = errorMessageChannel.receiveAsFlow()

    sealed class ErrorMessageEvent {
        data class InitiateUser(val errorMessage: String?) : ErrorMessageEvent()
        data class UniversalFeed(val errorMessage: String?) : ErrorMessageEvent()
        data class LikePost(val postId: String, val errorMessage: String?) : ErrorMessageEvent()
        data class SavePost(val postId: String, val errorMessage: String?) : ErrorMessageEvent()
        data class DeletePost(val errorMessage: String?) : ErrorMessageEvent()
        data class PinPost(val postId: String, val errorMessage: String?) : ErrorMessageEvent()
        data class AddPost(val errorMessage: String?) : ErrorMessageEvent()
    }

    companion object {
        const val PAGE_SIZE = 20
    }

    fun getUserUniqueId(): String {
        return userPreferences.getMemberId()
    }

    sealed class PostDataEvent {
        data class PostDbData(val post: PostViewData) : PostDataEvent()

        data class PostResponseData(val post: PostViewData) : PostDataEvent()
    }

    private val postDataEventChannel = Channel<PostDataEvent>(Channel.BUFFERED)
    val postDataEventFlow = postDataEventChannel.receiveAsFlow()

    /***
     * calls InitiateUser API
     * store user:{} in db
     * and posts the response in LiveData
     * */
    fun initiateUser(
        apiKey: String,
        userId: String,
        userName: String? = null,
        guest: Boolean
    ) {
        viewModelScope.launchIO {
            val request = InitiateUserRequest.Builder()
                .apiKey(apiKey)
                .deviceId(userPreferences.getDeviceId())
                .userId(userId)
                .userName(userName)
                .isGuest(guest)
                .build()

            //call api
            val initiateResponse = lmFeedClient.initiateUser(request)

            if (initiateResponse.success) {
                val data = initiateResponse.data ?: return@launchIO
                if (data.logoutResponse != null) {
                    //user is invalid
                    _logoutResponse.postValue(true)
                } else {
                    val user = data.user
                    val id = user?.userUniqueId ?: ""

                    //add user in local db
                    addUser(user)

                    //save user.id in local prefs
                    userPreferences.saveMemberId(id)

                    getUniversalFeed(1)

                    //post the user response in LiveData
                    _userResponse.postValue(ViewDataConverter.convertUser(user))
                }
            } else {
                errorMessageChannel.send(ErrorMessageEvent.InitiateUser(initiateResponse.errorMessage))
            }
        }
    }

    //add user:{} into local db
    private fun addUser(user: User?) {
        if (user == null) return
        viewModelScope.launchIO {
            //convert user into userEntity
            val userEntity = ViewDataConverter.createUserEntity(user)
            //add it to local db
            userRepository.insertUser(userEntity)

            //call member state api
            getMemberState()

            //call register device api
            registerDevice()
        }
    }

    //call member state api
    private fun getMemberState() {
        viewModelScope.launchIO {
            //get member state response
            val memberStateResponse = lmFeedClient.getMemberState().data

            val memberState = memberStateResponse?.state ?: return@launchIO
            val isOwner = memberStateResponse.isOwner
            val userId = memberStateResponse.userUniqueId

            //get existing userEntity
            var userEntity = userRepository.getUser(userId)

            //updated userEntity
            userEntity = userEntity.toBuilder().state(memberState).isOwner(isOwner).build()

            //update userEntity in local db
            userRepository.updateUser(userEntity)
        }
    }

    //call register device
    private fun registerDevice() {
        viewModelScope.launchIO {
            //create request
            val request = RegisterDeviceRequest.Builder()
                .deviceId(userPreferences.getDeviceId())
                .token("YUYUYUYUYUY") //todo fix it with proper token
                .build()

            //call api
            lmFeedClient.registerDevice(request)
        }
    }

    //get universal feed
    fun getUniversalFeed(page: Int) {
        viewModelScope.launchIO {
            val request = GetFeedRequest.Builder()
                .page(page)
                .pageSize(PAGE_SIZE)
                .build()

            //call universal feed api
            val response = lmFeedClient.getFeed(request)

            if (response.success) {
                val data = response.data ?: return@launchIO
                val posts = data.posts
                val usersMap = data.users

                //convert to view data
                val listOfPostViewData =
                    ViewDataConverter.convertUniversalFeedPosts(posts, usersMap)

                //send it to ui
                _universalFeedResponse.postValue(Pair(page, listOfPostViewData))
            } else {
                //for error
                errorMessageChannel.send(ErrorMessageEvent.UniversalFeed(response.errorMessage))
            }
        }
    }

    //for like/unlike a post
    fun likePost(postId: String) {
        viewModelScope.launchIO {
            val request = LikePostRequest.Builder()
                .postId(postId)
                .build()

            //call like post api
            val response = lmFeedClient.likePost(request)

            //check for error
            if (!response.success) {
                errorMessageChannel.send(ErrorMessageEvent.LikePost(postId, response.errorMessage))
            }
        }
    }

    //for save/un-save a post
    fun savePost(postId: String) {
        viewModelScope.launchIO {
            val request = SavePostRequest.Builder()
                .postId(postId)
                .build()

            //call save post api
            val response = lmFeedClient.savePost(request)

            //check for error
            if (!response.success) {
                errorMessageChannel.send(ErrorMessageEvent.SavePost(postId, response.errorMessage))
            }
        }
    }

    //for delete post
    fun deletePost(
        postId: String,
        reason: String? = null
    ) {
        viewModelScope.launchIO {
            val request = DeletePostRequest.Builder()
                .postId(postId)
                .deleteReason(reason)
                .build()

            //call delete post api
            val response = lmFeedClient.deletePost(request)

            if (response.success) {
                _deletePostResponse.postValue(postId)
            } else {
                errorMessageChannel.send(ErrorMessageEvent.DeletePost(response.errorMessage))
            }
        }
    }

    //for pin/unpin post
    fun pinPost(postId: String) {
        viewModelScope.launchIO {
            val request = PinPostRequest.Builder()
                .postId(postId)
                .build()

            //call pin api
            val response = lmFeedClient.pinPost(request)

            if (response.success) {
                _pinPostResponse.postValue(postId)
            } else {
                errorMessageChannel.send(ErrorMessageEvent.PinPost(postId, response.errorMessage))
            }
        }
    }

    // starts a media upload worker to retry failed uploads
    fun createRetryPostMediaWorker(
        context: Context,
        postId: Long?,
        attachmentCount: Int,
    ) {
        viewModelScope.launchIO {
            if (postId == null || attachmentCount <= 0) {
                return@launchIO
            }
            val uploadData = startMediaUploadWorker(context, postId, attachmentCount)
            postRepository.updateUploadWorkerUUID(postId, uploadData.second)
            uploadData.first.enqueue()
            checkIfPosting()
        }
    }

    // creates PostAttachmentUploadWorker to start media upload
    @SuppressLint("EnqueueWork")
    private fun startMediaUploadWorker(
        context: Context,
        postId: Long,
        filesCount: Int
    ): Pair<WorkContinuation, String> {
        val oneTimeWorkRequest = PostAttachmentUploadWorker.getInstance(postId, filesCount)
        val workContinuation = WorkManager.getInstance(context).beginWith(oneTimeWorkRequest)
        return Pair(workContinuation, oneTimeWorkRequest.id.toString())
    }

    // checks and sends the Post data if there is a post pending in db
    fun checkIfPosting() {
        viewModelScope.launchIO {
            val postWithAttachments = postRepository.getLatestPostWithAttachments()
            if (postWithAttachments == null || postWithAttachments.post.isPosted) {
                return@launchIO
            } else {
                temporaryPostId = postWithAttachments.post.id
                postDataEventChannel.send(PostDataEvent.PostDbData(convertPost(postWithAttachments)))
            }
        }
    }

    // calls AddPost API and posts the response in LiveData
    fun addPost(postingData: PostViewData) {
        viewModelScope.launchIO {
            val request = AddPostRequest.Builder()
                .text(postingData.text)
                .attachments(createAttachments(postingData.attachments))
                .build()

            val response = lmFeedClient.addPost(request)
            if (response.success) {
                val data = response.data ?: return@launchIO
                postDataEventChannel.send(
                    PostDataEvent.PostResponseData(
                        convertPost(
                            data.post,
                            data.users
                        )
                    )
                )
            } else {
                errorMessageChannel.send(ErrorMessageEvent.AddPost(response.errorMessage))
            }

            //set isPosted in db to true
            val id = temporaryPostId ?: 0
            postRepository.updateIsPosted(id, true)
        }
    }
}