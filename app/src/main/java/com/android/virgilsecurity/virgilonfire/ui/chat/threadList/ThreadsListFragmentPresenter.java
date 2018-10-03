/*
 * Copyright (c) 2015-2018, Virgil Security, Inc.
 *
 * Lead Maintainer: Virgil Security Inc. <support@virgilsecurity.com>
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *     (1) Redistributions of source code must retain the above copyright notice, this
 *     list of conditions and the following disclaimer.
 *
 *     (2) Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *
 *     (3) Neither the name of virgil nor the names of its
 *     contributors may be used to endorse or promote products derived from
 *     this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.virgilsecurity.virgilonfire.ui.chat.threadList;

import com.android.virgilsecurity.virgilonfire.data.model.DefaultChatThread;
import com.android.virgilsecurity.virgilonfire.data.model.DefaultUser;
import com.android.virgilsecurity.virgilonfire.data.model.exception.GenerateHashException;
import com.android.virgilsecurity.virgilonfire.data.model.request.CreateChannelRequest;
import com.android.virgilsecurity.virgilonfire.data.virgil.VirgilHelper;
import com.android.virgilsecurity.virgilonfire.ui.CompleteInteractor;
import com.android.virgilsecurity.virgilonfire.ui.base.BasePresenter;
import com.android.virgilsecurity.virgilonfire.ui.chat.DataReceivedInteractor;
import com.android.virgilsecurity.virgilonfire.util.UserUtils;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QuerySnapshot;
import com.virgilsecurity.sdk.crypto.HashAlgorithm;
import com.virgilsecurity.sdk.crypto.exceptions.CryptoException;
import com.virgilsecurity.sdk.utils.ConvertionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Inject;

import io.reactivex.Completable;
import io.reactivex.Scheduler;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

/**
 * . _  _
 * .| || | _
 * -| || || |   Created by:
 * .| || || |-  Danylo Oliinyk
 * ..\_  || |   on
 * ....|  _/    4/17/18
 * ...-| | \    at Virgil Security
 * ....|_|-
 */
public class ThreadsListFragmentPresenter implements BasePresenter {

    private static final String COLLECTION_CHANNELS = "Channels";
    private static final String COLLECTION_USERS = "Users";
    private static final String KEY_PROPERTY_MEMBERS = "members";
    private static final String KEY_PROPERTY_CHANNELS = "channels";
    private static final String KEY_PROPERTY_COUNT = "count";

    private final VirgilHelper virgilHelper;
    private final FirebaseFirestore firebaseFirestore;
    private final FirebaseAuth firebaseAuth;
    private final DataReceivedInteractor<List<DefaultChatThread>> onDataReceivedInteractor;
    private final CompleteInteractor<ThreadListFragmentPresenterReturnTypes> completeInteractor;

    private CompositeDisposable compositeDisposable;
    private ListenerRegistration listenerRegistration;

    @Inject
    public ThreadsListFragmentPresenter(FirebaseFirestore firebaseFirestore,
                                        FirebaseAuth firebaseAuth,
                                        VirgilHelper virgilHelper,
                                        DataReceivedInteractor<List<DefaultChatThread>> onDataReceivedInteractor,
                                        CompleteInteractor<ThreadListFragmentPresenterReturnTypes> completeInteractor) {
        this.firebaseFirestore = firebaseFirestore;
        this.firebaseAuth = firebaseAuth;
        this.virgilHelper = virgilHelper;
        this.onDataReceivedInteractor = onDataReceivedInteractor;
        this.completeInteractor = completeInteractor;

        compositeDisposable = new CompositeDisposable();
    }

    public void turnOnThreadsListener() {
        listenerRegistration =
                firebaseFirestore.collection(COLLECTION_CHANNELS)
                                 .addSnapshotListener((documentSnapshots, e) -> {
                                     firebaseFirestore.collection(COLLECTION_USERS)
                                                      .document(UserUtils.currentIdentity(firebaseAuth))
                                                      .get()
                                                      .addOnCompleteListener(task -> {
                                                          updateThreads(documentSnapshots, task);
                                                      });
                                 });
    }

    private void updateThreads(QuerySnapshot documentSnapshots, Task<DocumentSnapshot> task) {
        if (task.isSuccessful()) {
            DocumentSnapshot documentSnapshot = task.getResult();

            DefaultUser defaultUser = documentSnapshot.toObject(DefaultUser.class);
            defaultUser.setName(documentSnapshot.getId());

            List<DefaultChatThread> threads = new ArrayList<>();

            for (String channelId : defaultUser.getChannels()) {
                for (DocumentSnapshot document : documentSnapshots) {
                    if (document.getId().equals(channelId)) {
                        List<String> members = (List<String>) document.get(KEY_PROPERTY_MEMBERS);
                        long messagesCount = (Long) document.get(KEY_PROPERTY_COUNT);

                        String senderId = members.get(0).equals(UserUtils.currentIdentity(firebaseAuth))
                                          ? members.get(0) : members.get(1);
                        String receiverId = members.get(0)
                                                   .equals(senderId) ? members.get(1)
                                                                     : members.get(0);
                        threads.add(new DefaultChatThread(document.getId(),
                                                          senderId,
                                                          receiverId,
                                                          messagesCount));
                    }
                }
            }

            onDataReceivedInteractor.onDataReceived(threads);
        } else {
            onDataReceivedInteractor.onDataReceivedError(task.getException());
        }
    }

    public void turnOffThreadsListener() {
        if (listenerRegistration != null)
            listenerRegistration.remove();
    }

    public void requestCreateThread(String interlocutor) {
        String newThreadId = generateNewChannelId(interlocutor);

        Disposable requestCreateThreadDisposable =
                updateUserMe(newThreadId)
                        .andThen(updateUserInterlocutor(interlocutor, newThreadId))
                        .andThen(createThread(interlocutor, newThreadId))
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribeOn(Schedulers.io())
                        .subscribe(() -> completeInteractor.onComplete(ThreadListFragmentPresenterReturnTypes.CREATE_THREAD),
                                   completeInteractor::onError);

        compositeDisposable.add(requestCreateThreadDisposable);
    }

    public void requestRemoveThread(String interlocutor) {
        String newThreadId = generateNewChannelId(interlocutor);

        Disposable requestRemoveThreadDisposable =
                Completable.create(emitter -> {
                    firebaseFirestore.collection(COLLECTION_CHANNELS)
                                     .document(newThreadId)
                                     .delete()
                                     .addOnCompleteListener(task -> {
                                         if (task.isSuccessful())
                                             emitter.onComplete();
                                         else
                                             emitter.onError(task.getException());
                                     });
                }).observeOn(AndroidSchedulers.mainThread())
                           .subscribeOn(Schedulers.io())
                           .subscribe(() -> completeInteractor.onComplete(ThreadListFragmentPresenterReturnTypes.REMOVE_CHAT_THREAD),
                                      completeInteractor::onError);

        compositeDisposable.add(requestRemoveThreadDisposable);
    }

    private Completable createThread(String interlocutor, String newThreadId) {
        return Completable.create(emitter -> {
            List<String> members = new ArrayList<>();
            members.add(UserUtils.currentIdentity(firebaseAuth));
            members.add(interlocutor);
            CreateChannelRequest createChannelRequest = new CreateChannelRequest(members, 0);

            firebaseFirestore.collection(COLLECTION_CHANNELS)
                             .document(newThreadId)
                             .set(createChannelRequest)
                             .addOnCompleteListener(task -> {
                                 if (task.isSuccessful())
                                     emitter.onComplete();
                                 else
                                     emitter.onError(task.getException());
                             });
        });
    }

    private Single<List<String>> getUserChannels(String username) {
        return Single.create(emitter -> {
            firebaseFirestore.collection(COLLECTION_USERS)
                             .document(username)
                             .get()
                             .addOnCompleteListener(task -> {
                                 if (task.isSuccessful()) {
                                     emitter.onSuccess((List<String>) task.getResult().get(KEY_PROPERTY_CHANNELS));
                                 } else {
                                     emitter.onError(task.getException());
                                 }
                             });
        });
    }

    private Completable updateUserMe(String newThreadId) {
        return getUserChannels(UserUtils.currentIdentity(firebaseAuth))
                .flatMapCompletable(channels -> {
                    return Completable.create(emitter -> {
                        channels.add(newThreadId);
                        firebaseFirestore.collection(COLLECTION_USERS)
                                         .document(UserUtils.currentIdentity(firebaseAuth))
                                         .update("channels", channels)
                                         .addOnCompleteListener(task -> {
                                             if (task.isSuccessful())
                                                 emitter.onComplete();
                                             else
                                                 emitter.onError(task.getException());
                                         });
                    });
                });
    }

    private Completable updateUserInterlocutor(String interlocutor, String newThreadId) {
        return getUserChannels(interlocutor)
                .flatMapCompletable(channels -> {
                    return Completable.create(emitter -> {
                        channels.add(newThreadId);
                        firebaseFirestore.collection(COLLECTION_USERS)
                                         .document(interlocutor)
                                         .update("channels", channels)
                                         .addOnCompleteListener(task -> {
                                             if (task.isSuccessful())
                                                 emitter.onComplete();
                                             else
                                                 emitter.onError(task.getException());
                                         });
                    });
                });
    }

    private String generateNewChannelId(String interlocutor) {
        String userMe = UserUtils.currentIdentity(firebaseAuth);
        byte[] concatenatedHashedUsersData;

        try {
            if (userMe.compareTo(interlocutor) >= 0) {
                concatenatedHashedUsersData = virgilHelper.getVirgilCrypto()
                                                          .generateHash((userMe + interlocutor).getBytes(),
                                                                        HashAlgorithm.SHA256);
            } else {
                concatenatedHashedUsersData = virgilHelper.getVirgilCrypto()
                                                          .generateHash((interlocutor + userMe).getBytes(),
                                                                        HashAlgorithm.SHA256);
            }
        } catch (CryptoException e) {
            e.printStackTrace();
            throw new GenerateHashException();
        }

        return ConvertionUtils.toHex(concatenatedHashedUsersData).toLowerCase();
    }

    @Override
    public void disposeAll() {
        compositeDisposable.clear();
    }
}
