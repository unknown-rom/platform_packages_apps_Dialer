/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.dialer.app.calllog;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.net.Uri;
import android.os.AsyncTask;
import android.provider.CallLog;
import android.provider.CallLog.Calls;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.support.annotation.VisibleForTesting;
import android.support.v7.widget.CardView;
import android.support.v7.widget.RecyclerView;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.PhoneNumberUtils;
import android.text.BidiFormatter;
import android.text.TextDirectionHeuristics;
import android.text.TextUtils;
import android.view.ContextMenu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewStub;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.QuickContactBadge;
import android.widget.TextView;
import com.android.contacts.common.ClipboardUtils;
import com.android.contacts.common.ContactPhotoManager;
import com.android.contacts.common.ContactPhotoManager.DefaultImageRequest;
import com.android.contacts.common.compat.PhoneNumberUtilsCompat;
import com.android.contacts.common.dialog.CallSubjectDialog;
import com.android.contacts.common.util.UriUtils;
import com.android.dialer.app.DialtactsActivity;
import com.android.dialer.app.R;
import com.android.dialer.app.calllog.calllogcache.CallLogCache;
import com.android.dialer.app.voicemail.VoicemailPlaybackLayout;
import com.android.dialer.app.voicemail.VoicemailPlaybackPresenter;
import com.android.dialer.blocking.BlockedNumbersMigrator;
import com.android.dialer.blocking.FilteredNumberCompat;
import com.android.dialer.blocking.FilteredNumbersUtil;
import com.android.dialer.callcomposer.CallComposerActivity;
import com.android.dialer.callcomposer.nano.CallComposerContact;
import com.android.dialer.calldetails.nano.CallDetailsEntries;
import com.android.dialer.common.LogUtil;
import com.android.dialer.compat.CompatUtils;
import com.android.dialer.logging.Logger;
import com.android.dialer.logging.nano.DialerImpression;
import com.android.dialer.logging.nano.ScreenEvent;
import com.android.dialer.phonenumbercache.ContactInfo;
import com.android.dialer.phonenumberutil.PhoneNumberHelper;
import com.android.dialer.util.CallUtil;
import com.android.dialer.util.DialerUtils;

/**
 * This is an object containing references to views contained by the call log list item. This
 * improves performance by reducing the frequency with which we need to find views by IDs.
 *
 * <p>This object also contains UI logic pertaining to the view, to isolate it from the
 * CallLogAdapter.
 */
public final class CallLogListItemViewHolder extends RecyclerView.ViewHolder
    implements View.OnClickListener,
        MenuItem.OnMenuItemClickListener,
        View.OnCreateContextMenuListener {
  /** The root view of the call log list item */
  public final View rootView;
  /** The quick contact badge for the contact. */
  public final QuickContactBadge quickContactView;
  /** The primary action view of the entry. */
  public final View primaryActionView;
  /** The details of the phone call. */
  public final PhoneCallDetailsViews phoneCallDetailsViews;
  /** The text of the header for a day grouping. */
  public final TextView dayGroupHeader;
  /** The view containing the details for the call log row, including the action buttons. */
  public final CardView callLogEntryView;
  /** The actionable view which places a call to the number corresponding to the call log row. */
  public final ImageView primaryActionButtonView;

  private final Context mContext;
  private final CallLogCache mCallLogCache;
  private final CallLogListItemHelper mCallLogListItemHelper;
  private final VoicemailPlaybackPresenter mVoicemailPlaybackPresenter;
  private final OnClickListener mBlockReportListener;
  private final int mPhotoSize;
  /** Whether the data fields are populated by the worker thread, ready to be shown. */
  public boolean isLoaded;
  /** The view containing call log item actions. Null until the ViewStub is inflated. */
  public View actionsView;
  /** The button views below are assigned only when the action section is expanded. */
  public VoicemailPlaybackLayout voicemailPlaybackView;

  public View callButtonView;
  public View videoCallButtonView;
  public View createNewContactButtonView;
  public View addToExistingContactButtonView;
  public View sendMessageView;
  public View blockReportView;
  public View blockView;
  public View unblockView;
  public View reportNotSpamView;
  public View detailsButtonView;
  public View callWithNoteButtonView;
  public View callComposeButtonView;
  public View sendVoicemailButtonView;
  public ImageView workIconView;
  /**
   * The row Id for the first call associated with the call log entry. Used as a key for the map
   * used to track which call log entries have the action button section expanded.
   */
  public long rowId;
  /**
   * The call Ids for the calls represented by the current call log entry. Used when the user
   * deletes a call log entry.
   */
  public long[] callIds;
  /**
   * The callable phone number for the current call log entry. Cached here as the call back intent
   * is set only when the actions ViewStub is inflated.
   */
  public String number;
  /** The post-dial numbers that are dialed following the phone number. */
  public String postDialDigits;
  /** The formatted phone number to display. */
  public String displayNumber;
  /**
   * The phone number presentation for the current call log entry. Cached here as the call back
   * intent is set only when the actions ViewStub is inflated.
   */
  public int numberPresentation;
  /** The type of the phone number (e.g. main, work, etc). */
  public String numberType;
  /**
   * The country iso for the call. Cached here as the call back intent is set only when the actions
   * ViewStub is inflated.
   */
  public String countryIso;
  /**
   * The type of call for the current call log entry. Cached here as the call back intent is set
   * only when the actions ViewStub is inflated.
   */
  public int callType;
  /**
   * ID for blocked numbers database. Set when context menu is created, if the number is blocked.
   */
  public Integer blockId;
  /**
   * The account for the current call log entry. Cached here as the call back intent is set only
   * when the actions ViewStub is inflated.
   */
  public PhoneAccountHandle accountHandle;
  /**
   * If the call has an associated voicemail message, the URI of the voicemail message for playback.
   * Cached here as the voicemail intent is only set when the actions ViewStub is inflated.
   */
  public String voicemailUri;
  /**
   * The name or number associated with the call. Cached here for use when setting content
   * descriptions on buttons in the actions ViewStub when it is inflated.
   */
  public CharSequence nameOrNumber;
  /**
   * The call type or Location associated with the call. Cached here for use when setting text for a
   * voicemail log's call button
   */
  public CharSequence callTypeOrLocation;
  /** Whether this row is for a business or not. */
  public boolean isBusiness;
  /** The contact info for the contact displayed in this list item. */
  public volatile ContactInfo info;
  /** Whether spam feature is enabled, which affects UI. */
  public boolean isSpamFeatureEnabled;
  /** Whether the current log entry is a spam number or not. */
  public boolean isSpam;

  public boolean isCallComposerCapable;

  private View.OnClickListener mExpandCollapseListener;
  private boolean mVoicemailPrimaryActionButtonClicked;

  public int dayGroupHeaderVisibility;
  public CharSequence dayGroupHeaderText;
  public boolean isAttachedToWindow;

  public AsyncTask<Void, Void, ?> asyncTask;
  private CallDetailsEntries callDetailsEntries;

  private CallLogListItemViewHolder(
      Context context,
      OnClickListener blockReportListener,
      View.OnClickListener expandCollapseListener,
      CallLogCache callLogCache,
      CallLogListItemHelper callLogListItemHelper,
      VoicemailPlaybackPresenter voicemailPlaybackPresenter,
      View rootView,
      QuickContactBadge quickContactView,
      View primaryActionView,
      PhoneCallDetailsViews phoneCallDetailsViews,
      CardView callLogEntryView,
      TextView dayGroupHeader,
      ImageView primaryActionButtonView) {
    super(rootView);

    mContext = context;
    mExpandCollapseListener = expandCollapseListener;
    mCallLogCache = callLogCache;
    mCallLogListItemHelper = callLogListItemHelper;
    mVoicemailPlaybackPresenter = voicemailPlaybackPresenter;
    mBlockReportListener = blockReportListener;

    this.rootView = rootView;
    this.quickContactView = quickContactView;
    this.primaryActionView = primaryActionView;
    this.phoneCallDetailsViews = phoneCallDetailsViews;
    this.callLogEntryView = callLogEntryView;
    this.dayGroupHeader = dayGroupHeader;
    this.primaryActionButtonView = primaryActionButtonView;
    this.workIconView = (ImageView) rootView.findViewById(R.id.work_profile_icon);
    mPhotoSize = mContext.getResources().getDimensionPixelSize(R.dimen.contact_photo_size);

    // Set text height to false on the TextViews so they don't have extra padding.
    phoneCallDetailsViews.nameView.setElegantTextHeight(false);
    phoneCallDetailsViews.callLocationAndDate.setElegantTextHeight(false);

    quickContactView.setOverlay(null);
    if (CompatUtils.hasPrioritizedMimeType()) {
      quickContactView.setPrioritizedMimeType(Phone.CONTENT_ITEM_TYPE);
    }
    primaryActionButtonView.setOnClickListener(this);
    primaryActionView.setOnClickListener(mExpandCollapseListener);
    primaryActionView.setOnCreateContextMenuListener(this);
  }

  public static CallLogListItemViewHolder create(
      View view,
      Context context,
      OnClickListener blockReportListener,
      View.OnClickListener expandCollapseListener,
      CallLogCache callLogCache,
      CallLogListItemHelper callLogListItemHelper,
      VoicemailPlaybackPresenter voicemailPlaybackPresenter) {

    return new CallLogListItemViewHolder(
        context,
        blockReportListener,
        expandCollapseListener,
        callLogCache,
        callLogListItemHelper,
        voicemailPlaybackPresenter,
        view,
        (QuickContactBadge) view.findViewById(R.id.quick_contact_photo),
        view.findViewById(R.id.primary_action_view),
        PhoneCallDetailsViews.fromView(view),
        (CardView) view.findViewById(R.id.call_log_row),
        (TextView) view.findViewById(R.id.call_log_day_group_label),
        (ImageView) view.findViewById(R.id.primary_action_button));
  }

  public static CallLogListItemViewHolder createForTest(Context context) {
    Resources resources = context.getResources();
    CallLogCache callLogCache = CallLogCache.getCallLogCache(context);
    PhoneCallDetailsHelper phoneCallDetailsHelper =
        new PhoneCallDetailsHelper(context, resources, callLogCache);

    CallLogListItemViewHolder viewHolder =
        new CallLogListItemViewHolder(
            context,
            null,
            null /* expandCollapseListener */,
            callLogCache,
            new CallLogListItemHelper(phoneCallDetailsHelper, resources, callLogCache),
            null /* voicemailPlaybackPresenter */,
            new View(context),
            new QuickContactBadge(context),
            new View(context),
            PhoneCallDetailsViews.createForTest(context),
            new CardView(context),
            new TextView(context),
            new ImageView(context));
    viewHolder.detailsButtonView = new TextView(context);
    viewHolder.actionsView = new View(context);
    viewHolder.voicemailPlaybackView = new VoicemailPlaybackLayout(context);
    viewHolder.workIconView = new ImageButton(context);
    return viewHolder;
  }

  @Override
  public void onCreateContextMenu(
      final ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
    if (TextUtils.isEmpty(number)) {
      return;
    }

    if (callType == CallLog.Calls.VOICEMAIL_TYPE) {
      menu.setHeaderTitle(mContext.getResources().getText(R.string.voicemail));
    } else {
      menu.setHeaderTitle(
          PhoneNumberUtilsCompat.createTtsSpannable(
              BidiFormatter.getInstance().unicodeWrap(number, TextDirectionHeuristics.LTR)));
    }

    menu.add(
            ContextMenu.NONE,
            R.id.context_menu_copy_to_clipboard,
            ContextMenu.NONE,
            R.string.action_copy_number_text)
        .setOnMenuItemClickListener(this);

    // The edit number before call does not show up if any of the conditions apply:
    // 1) Number cannot be called
    // 2) Number is the voicemail number
    // 3) Number is a SIP address

    if (PhoneNumberHelper.canPlaceCallsTo(number, numberPresentation)
        && !mCallLogCache.isVoicemailNumber(accountHandle, number)
        && !PhoneNumberHelper.isSipNumber(number)) {
      menu.add(
              ContextMenu.NONE,
              R.id.context_menu_edit_before_call,
              ContextMenu.NONE,
              R.string.action_edit_number_before_call)
          .setOnMenuItemClickListener(this);
    }

    if (callType == CallLog.Calls.VOICEMAIL_TYPE
        && phoneCallDetailsViews.voicemailTranscriptionView.length() > 0) {
      menu.add(
              ContextMenu.NONE,
              R.id.context_menu_copy_transcript_to_clipboard,
              ContextMenu.NONE,
              R.string.copy_transcript_text)
          .setOnMenuItemClickListener(this);
    }

    String e164Number = PhoneNumberUtils.formatNumberToE164(number, countryIso);
    boolean isVoicemailNumber = mCallLogCache.isVoicemailNumber(accountHandle, number);
    if (!isVoicemailNumber
        && FilteredNumbersUtil.canBlockNumber(mContext, e164Number, number)
        && FilteredNumberCompat.canAttemptBlockOperations(mContext)) {
      boolean isBlocked = blockId != null;
      if (isBlocked) {
        menu.add(
                ContextMenu.NONE,
                R.id.context_menu_unblock,
                ContextMenu.NONE,
                R.string.call_log_action_unblock_number)
            .setOnMenuItemClickListener(this);
      } else {
        if (isSpamFeatureEnabled) {
          if (isSpam) {
            menu.add(
                    ContextMenu.NONE,
                    R.id.context_menu_report_not_spam,
                    ContextMenu.NONE,
                    R.string.call_log_action_remove_spam)
                .setOnMenuItemClickListener(this);
            menu.add(
                    ContextMenu.NONE,
                    R.id.context_menu_block,
                    ContextMenu.NONE,
                    R.string.call_log_action_block_number)
                .setOnMenuItemClickListener(this);
          } else {
            menu.add(
                    ContextMenu.NONE,
                    R.id.context_menu_block_report_spam,
                    ContextMenu.NONE,
                    R.string.call_log_action_block_report_number)
                .setOnMenuItemClickListener(this);
          }
        } else {
          menu.add(
                  ContextMenu.NONE,
                  R.id.context_menu_block,
                  ContextMenu.NONE,
                  R.string.call_log_action_block_number)
              .setOnMenuItemClickListener(this);
        }
      }
    }

    Logger.get(mContext).logScreenView(ScreenEvent.Type.CALL_LOG_CONTEXT_MENU, (Activity) mContext);
  }

  @Override
  public boolean onMenuItemClick(MenuItem item) {
    int resId = item.getItemId();
    if (resId == R.id.context_menu_copy_to_clipboard) {
      ClipboardUtils.copyText(mContext, null, number, true);
      return true;
    } else if (resId == R.id.context_menu_copy_transcript_to_clipboard) {
      ClipboardUtils.copyText(
          mContext, null, phoneCallDetailsViews.voicemailTranscriptionView.getText(), true);
      return true;
    } else if (resId == R.id.context_menu_edit_before_call) {
      final Intent intent = new Intent(Intent.ACTION_DIAL, CallUtil.getCallUri(number));
      intent.setClass(mContext, DialtactsActivity.class);
      DialerUtils.startActivityWithErrorToast(mContext, intent);
      return true;
    } else if (resId == R.id.context_menu_block_report_spam) {
      Logger.get(mContext)
          .logImpression(DialerImpression.Type.CALL_LOG_CONTEXT_MENU_BLOCK_REPORT_SPAM);
      maybeShowBlockNumberMigrationDialog(
          new BlockedNumbersMigrator.Listener() {
            @Override
            public void onComplete() {
              mBlockReportListener.onBlockReportSpam(
                  displayNumber, number, countryIso, callType, info.sourceType);
            }
          });
    } else if (resId == R.id.context_menu_block) {
      Logger.get(mContext).logImpression(DialerImpression.Type.CALL_LOG_CONTEXT_MENU_BLOCK_NUMBER);
      maybeShowBlockNumberMigrationDialog(
          new BlockedNumbersMigrator.Listener() {
            @Override
            public void onComplete() {
              mBlockReportListener.onBlock(
                  displayNumber, number, countryIso, callType, info.sourceType);
            }
          });
    } else if (resId == R.id.context_menu_unblock) {
      Logger.get(mContext)
          .logImpression(DialerImpression.Type.CALL_LOG_CONTEXT_MENU_UNBLOCK_NUMBER);
      mBlockReportListener.onUnblock(
          displayNumber, number, countryIso, callType, info.sourceType, isSpam, blockId);
    } else if (resId == R.id.context_menu_report_not_spam) {
      Logger.get(mContext)
          .logImpression(DialerImpression.Type.CALL_LOG_CONTEXT_MENU_REPORT_AS_NOT_SPAM);
      mBlockReportListener.onReportNotSpam(
          displayNumber, number, countryIso, callType, info.sourceType);
    }
    return false;
  }

  /**
   * Configures the action buttons in the expandable actions ViewStub. The ViewStub is not inflated
   * during initial binding, so click handlers, tags and accessibility text must be set here, if
   * necessary.
   */
  public void inflateActionViewStub() {
    ViewStub stub = (ViewStub) rootView.findViewById(R.id.call_log_entry_actions_stub);
    if (stub != null) {
      actionsView = stub.inflate();

      voicemailPlaybackView =
          (VoicemailPlaybackLayout) actionsView.findViewById(R.id.voicemail_playback_layout);
      voicemailPlaybackView.setViewHolder(this);

      callButtonView = actionsView.findViewById(R.id.call_action);
      callButtonView.setOnClickListener(this);

      videoCallButtonView = actionsView.findViewById(R.id.video_call_action);
      videoCallButtonView.setOnClickListener(this);

      createNewContactButtonView = actionsView.findViewById(R.id.create_new_contact_action);
      createNewContactButtonView.setOnClickListener(this);

      addToExistingContactButtonView =
          actionsView.findViewById(R.id.add_to_existing_contact_action);
      addToExistingContactButtonView.setOnClickListener(this);

      sendMessageView = actionsView.findViewById(R.id.send_message_action);
      sendMessageView.setOnClickListener(this);

      blockReportView = actionsView.findViewById(R.id.block_report_action);
      blockReportView.setOnClickListener(this);

      blockView = actionsView.findViewById(R.id.block_action);
      blockView.setOnClickListener(this);

      unblockView = actionsView.findViewById(R.id.unblock_action);
      unblockView.setOnClickListener(this);

      reportNotSpamView = actionsView.findViewById(R.id.report_not_spam_action);
      reportNotSpamView.setOnClickListener(this);

      detailsButtonView = actionsView.findViewById(R.id.details_action);
      detailsButtonView.setOnClickListener(this);

      callWithNoteButtonView = actionsView.findViewById(R.id.call_with_note_action);
      callWithNoteButtonView.setOnClickListener(this);

      callComposeButtonView = actionsView.findViewById(R.id.call_compose_action);
      callComposeButtonView.setOnClickListener(this);

      sendVoicemailButtonView = actionsView.findViewById(R.id.share_voicemail);
      sendVoicemailButtonView.setOnClickListener(this);
    }
  }

  private void updatePrimaryActionButton(boolean isExpanded) {

    if (nameOrNumber == null) {
      LogUtil.e("CallLogListItemViewHolder.updatePrimaryActionButton", "name or number is null");
    }

    // Calling expandTemplate with a null parameter will cause a NullPointerException.
    CharSequence validNameOrNumber = nameOrNumber == null ? "" : nameOrNumber;

    if (!TextUtils.isEmpty(voicemailUri)) {
      // Treat as voicemail list item; show play button if not expanded.
      if (!isExpanded) {
        primaryActionButtonView.setImageResource(R.drawable.ic_play_arrow_24dp);
        primaryActionButtonView.setContentDescription(
            TextUtils.expandTemplate(
                mContext.getString(R.string.description_voicemail_action), validNameOrNumber));
        primaryActionButtonView.setVisibility(View.VISIBLE);
      } else {
        primaryActionButtonView.setVisibility(View.GONE);
      }
    } else {
      // Treat as normal list item; show call button, if possible.
      if (PhoneNumberHelper.canPlaceCallsTo(number, numberPresentation)) {
        boolean isVoicemailNumber = mCallLogCache.isVoicemailNumber(accountHandle, number);
        if (isVoicemailNumber) {
          // Call to generic voicemail number, in case there are multiple accounts.
          primaryActionButtonView.setTag(IntentProvider.getReturnVoicemailCallIntentProvider());
        } else {
          primaryActionButtonView.setTag(
              IntentProvider.getReturnCallIntentProvider(number + postDialDigits));
        }

        primaryActionButtonView.setContentDescription(
            TextUtils.expandTemplate(
                mContext.getString(R.string.description_call_action), validNameOrNumber));
        primaryActionButtonView.setImageResource(R.drawable.ic_call_24dp);
        primaryActionButtonView.setVisibility(View.VISIBLE);
      } else {
        primaryActionButtonView.setTag(null);
        primaryActionButtonView.setVisibility(View.GONE);
      }
    }
  }

  /**
   * Binds text titles, click handlers and intents to the voicemail, details and callback action
   * buttons.
   */
  private void bindActionButtons() {
    boolean canPlaceCallToNumber = PhoneNumberHelper.canPlaceCallsTo(number, numberPresentation);

    if (isFullyUndialableVoicemail()) {
      // Sometimes the voicemail server will report the message is from some non phone number
      // source. If the number does not contains any dialable digit treat it as it is from a unknown
      // number, remove all action buttons but still show the voicemail playback layout.
      callButtonView.setVisibility(View.GONE);
      videoCallButtonView.setVisibility(View.GONE);
      detailsButtonView.setVisibility(View.GONE);
      createNewContactButtonView.setVisibility(View.GONE);
      addToExistingContactButtonView.setVisibility(View.GONE);
      sendMessageView.setVisibility(View.GONE);
      callWithNoteButtonView.setVisibility(View.GONE);
      callComposeButtonView.setVisibility(View.GONE);
      blockReportView.setVisibility(View.GONE);
      blockView.setVisibility(View.GONE);
      unblockView.setVisibility(View.GONE);
      reportNotSpamView.setVisibility(View.GONE);

      voicemailPlaybackView.setVisibility(View.VISIBLE);
      Uri uri = Uri.parse(voicemailUri);
      mVoicemailPlaybackPresenter.setPlaybackView(
          voicemailPlaybackView,
          rowId,
          uri,
          mVoicemailPrimaryActionButtonClicked,
          sendVoicemailButtonView);
      mVoicemailPrimaryActionButtonClicked = false;
      CallLogAsyncTaskUtil.markVoicemailAsRead(mContext, uri);
      return;
    }

    if (!TextUtils.isEmpty(voicemailUri) && canPlaceCallToNumber) {
      callButtonView.setTag(IntentProvider.getReturnCallIntentProvider(number));
      ((TextView) callButtonView.findViewById(R.id.call_action_text))
          .setText(
              TextUtils.expandTemplate(
                  mContext.getString(R.string.call_log_action_call),
                  nameOrNumber == null ? "" : nameOrNumber));
      TextView callTypeOrLocationView =
          ((TextView) callButtonView.findViewById(R.id.call_type_or_location_text));
      if (callType == Calls.VOICEMAIL_TYPE && !TextUtils.isEmpty(callTypeOrLocation)) {
        callTypeOrLocationView.setText(callTypeOrLocation);
        callTypeOrLocationView.setVisibility(View.VISIBLE);
      } else {
        callTypeOrLocationView.setVisibility(View.GONE);
      }
      callButtonView.setVisibility(View.VISIBLE);
    } else {
      callButtonView.setVisibility(View.GONE);
    }

    if (shouldShowVideoCallActionButton(canPlaceCallToNumber)) {
      videoCallButtonView.setTag(IntentProvider.getReturnVideoCallIntentProvider(number));
      videoCallButtonView.setVisibility(View.VISIBLE);
    } else {
      videoCallButtonView.setVisibility(View.GONE);
    }

    // For voicemail calls, show the voicemail playback layout; hide otherwise.
    if (callType == Calls.VOICEMAIL_TYPE
        && mVoicemailPlaybackPresenter != null
        && !TextUtils.isEmpty(voicemailUri)) {
      voicemailPlaybackView.setVisibility(View.VISIBLE);

      Uri uri = Uri.parse(voicemailUri);
      mVoicemailPlaybackPresenter.setPlaybackView(
          voicemailPlaybackView,
          rowId,
          uri,
          mVoicemailPrimaryActionButtonClicked,
          sendVoicemailButtonView);
      mVoicemailPrimaryActionButtonClicked = false;
      CallLogAsyncTaskUtil.markVoicemailAsRead(mContext, uri);
    } else {
      voicemailPlaybackView.setVisibility(View.GONE);
      sendVoicemailButtonView.setVisibility(View.GONE);
    }

    if (callType == Calls.VOICEMAIL_TYPE) {
      detailsButtonView.setVisibility(View.GONE);
    } else {
      detailsButtonView.setVisibility(View.VISIBLE);
      detailsButtonView.setTag(
          IntentProvider.getCallDetailIntentProvider(callDetailsEntries, buildContact()));
    }

    boolean isBlockedOrSpam = blockId != null || (isSpamFeatureEnabled && isSpam);

    if (!isBlockedOrSpam && info != null && UriUtils.isEncodedContactUri(info.lookupUri)) {
      createNewContactButtonView.setTag(
          IntentProvider.getAddContactIntentProvider(
              info.lookupUri, info.name, info.number, info.type, true /* isNewContact */));
      createNewContactButtonView.setVisibility(View.VISIBLE);

      addToExistingContactButtonView.setTag(
          IntentProvider.getAddContactIntentProvider(
              info.lookupUri, info.name, info.number, info.type, false /* isNewContact */));
      addToExistingContactButtonView.setVisibility(View.VISIBLE);
    } else {
      createNewContactButtonView.setVisibility(View.GONE);
      addToExistingContactButtonView.setVisibility(View.GONE);
    }

    if (canPlaceCallToNumber && !isBlockedOrSpam) {
      sendMessageView.setTag(IntentProvider.getSendSmsIntentProvider(number));
      sendMessageView.setVisibility(View.VISIBLE);
    } else {
      sendMessageView.setVisibility(View.GONE);
    }

    mCallLogListItemHelper.setActionContentDescriptions(this);

    boolean supportsCallSubject = mCallLogCache.doesAccountSupportCallSubject(accountHandle);
    boolean isVoicemailNumber = mCallLogCache.isVoicemailNumber(accountHandle, number);
    callWithNoteButtonView.setVisibility(
        supportsCallSubject && !isVoicemailNumber && info != null ? View.VISIBLE : View.GONE);

    callComposeButtonView.setVisibility(isCallComposerCapable ? View.VISIBLE : View.GONE);

    updateBlockReportActions(isVoicemailNumber);
  }

  private boolean isFullyUndialableVoicemail() {
    if (callType == Calls.VOICEMAIL_TYPE) {
      if (!hasDialableChar(number)) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasDialableChar(CharSequence number) {
    if (TextUtils.isEmpty(number)) {
      return false;
    }
    for (char c : number.toString().toCharArray()) {
      if (PhoneNumberUtils.isDialable(c)) {
        return true;
      }
    }
    return false;
  }

  private boolean shouldShowVideoCallActionButton(boolean canPlaceCallToNumber) {
    return canPlaceCallToNumber && (hasPlacedVideoCall() || canSupportVideoCall());
  }

  private boolean hasPlacedVideoCall() {
    return phoneCallDetailsViews.callTypeIcons.isVideoShown();
  }

  private boolean canSupportVideoCall() {
    return mCallLogCache.canRelyOnVideoPresence()
        && info != null
        && (info.carrierPresence & Phone.CARRIER_PRESENCE_VT_CAPABLE) != 0;
  }

  /**
   * Show or hide the action views, such as voicemail, details, and add contact.
   *
   * <p>If the action views have never been shown yet for this view, inflate the view stub.
   */
  public void showActions(boolean show) {
    showOrHideVoicemailTranscriptionView(show);

    if (show) {
      if (!isLoaded) {
        // b/31268128 for some unidentified reason showActions() can be called before the item is
        // loaded, causing NPE on uninitialized fields. Just log and return here, showActions() will
        // be called again once the item is loaded.
        LogUtil.e(
            "CallLogListItemViewHolder.showActions",
            "called before item is loaded",
            new Exception());
        return;
      }

      // Inflate the view stub if necessary, and wire up the event handlers.
      inflateActionViewStub();
      bindActionButtons();
      actionsView.setVisibility(View.VISIBLE);
      actionsView.setAlpha(1.0f);
    } else {
      // When recycling a view, it is possible the actionsView ViewStub was previously
      // inflated so we should hide it in this case.
      if (actionsView != null) {
        actionsView.setVisibility(View.GONE);
      }
    }

    updatePrimaryActionButton(show);
  }

  public void showOrHideVoicemailTranscriptionView(boolean isExpanded) {
    if (callType != Calls.VOICEMAIL_TYPE) {
      return;
    }

    final TextView view = phoneCallDetailsViews.voicemailTranscriptionView;
    if (!isExpanded || TextUtils.isEmpty(view.getText())) {
      view.setVisibility(View.GONE);
      return;
    }
    view.setVisibility(View.VISIBLE);
  }

  public void updatePhoto() {
    quickContactView.assignContactUri(info.lookupUri);

    if (isSpamFeatureEnabled && isSpam) {
      quickContactView.setImageDrawable(mContext.getDrawable(R.drawable.blocked_contact));
      return;
    }
    final boolean isVoicemail = mCallLogCache.isVoicemailNumber(accountHandle, number);
    int contactType = ContactPhotoManager.TYPE_DEFAULT;
    if (isVoicemail) {
      contactType = ContactPhotoManager.TYPE_VOICEMAIL;
    } else if (isBusiness) {
      contactType = ContactPhotoManager.TYPE_BUSINESS;
    } else if (numberPresentation == TelecomManager.PRESENTATION_RESTRICTED) {
      contactType = ContactPhotoManager.TYPE_GENERIC_AVATAR;
    }

    final String lookupKey =
        info.lookupUri != null ? UriUtils.getLookupKeyFromUri(info.lookupUri) : null;
    final String displayName = TextUtils.isEmpty(info.name) ? displayNumber : info.name;
    final DefaultImageRequest request =
        new DefaultImageRequest(displayName, lookupKey, contactType, true /* isCircular */);

    if (info.photoId == 0 && info.photoUri != null) {
      ContactPhotoManager.getInstance(mContext)
          .loadPhoto(
              quickContactView,
              info.photoUri,
              mPhotoSize,
              false /* darkTheme */,
              true /* isCircular */,
              request);
    } else {
      ContactPhotoManager.getInstance(mContext)
          .loadThumbnail(
              quickContactView,
              info.photoId,
              false /* darkTheme */,
              true /* isCircular */,
              request);
    }
  }

  @Override
  public void onClick(View view) {
    if (view.getId() == R.id.primary_action_button && !TextUtils.isEmpty(voicemailUri)) {
      Logger.get(mContext).logImpression(DialerImpression.Type.VOICEMAIL_PLAY_AUDIO_DIRECTLY);
      mVoicemailPrimaryActionButtonClicked = true;
      mExpandCollapseListener.onClick(primaryActionView);
    } else if (view.getId() == R.id.call_with_note_action) {
      CallSubjectDialog.start(
          (Activity) mContext,
          info.photoId,
          info.photoUri,
          info.lookupUri,
          (String) nameOrNumber /* top line of contact view in call subject dialog */,
          isBusiness,
          number,
          TextUtils.isEmpty(info.name) ? null : displayNumber, /* second line of contact
                                                                           view in dialog. */
          numberType, /* phone number type (e.g. mobile) in second line of contact view */
          accountHandle);
    } else if (view.getId() == R.id.block_report_action) {
      Logger.get(mContext).logImpression(DialerImpression.Type.CALL_LOG_BLOCK_REPORT_SPAM);
      maybeShowBlockNumberMigrationDialog(
          new BlockedNumbersMigrator.Listener() {
            @Override
            public void onComplete() {
              mBlockReportListener.onBlockReportSpam(
                  displayNumber, number, countryIso, callType, info.sourceType);
            }
          });
    } else if (view.getId() == R.id.block_action) {
      Logger.get(mContext).logImpression(DialerImpression.Type.CALL_LOG_BLOCK_NUMBER);
      maybeShowBlockNumberMigrationDialog(
          new BlockedNumbersMigrator.Listener() {
            @Override
            public void onComplete() {
              mBlockReportListener.onBlock(
                  displayNumber, number, countryIso, callType, info.sourceType);
            }
          });
    } else if (view.getId() == R.id.unblock_action) {
      Logger.get(mContext).logImpression(DialerImpression.Type.CALL_LOG_UNBLOCK_NUMBER);
      mBlockReportListener.onUnblock(
          displayNumber, number, countryIso, callType, info.sourceType, isSpam, blockId);
    } else if (view.getId() == R.id.report_not_spam_action) {
      Logger.get(mContext).logImpression(DialerImpression.Type.CALL_LOG_REPORT_AS_NOT_SPAM);
      mBlockReportListener.onReportNotSpam(
          displayNumber, number, countryIso, callType, info.sourceType);
    } else if (view.getId() == R.id.call_compose_action) {
      LogUtil.i("CallLogListItemViewHolder.onClick", "share and call pressed");
      Logger.get(mContext).logImpression(DialerImpression.Type.CALL_LOG_SHARE_AND_CALL);
      Activity activity = (Activity) mContext;
      activity.startActivityForResult(
          CallComposerActivity.newIntent(activity, buildContact()),
          DialtactsActivity.ACTIVITY_REQUEST_CODE_CALL_COMPOSE);
    } else if (view.getId() == R.id.share_voicemail) {
      Logger.get(mContext).logImpression(DialerImpression.Type.VVM_SHARE_PRESSED);
      mVoicemailPlaybackPresenter.shareVoicemail();
    } else {
      logCallLogAction(view.getId());
      final IntentProvider intentProvider = (IntentProvider) view.getTag();
      if (intentProvider != null) {
        final Intent intent = intentProvider.getIntent(mContext);
        // See IntentProvider.getCallDetailIntentProvider() for why this may be null.
        if (intent != null) {
          DialerUtils.startActivityWithErrorToast(mContext, intent);
        }
      }
    }
  }

  private CallComposerContact buildContact() {
    CallComposerContact contact = new CallComposerContact();
    contact.photoId = info.photoId;
    contact.photoUri = info.photoUri == null ? null : info.photoUri.toString();
    contact.contactUri = info.lookupUri == null ? null : info.lookupUri.toString();
    contact.nameOrNumber = (String) nameOrNumber;
    contact.isBusiness = isBusiness;
    contact.number = number;
    /* second line of contact view. */
    contact.displayNumber = TextUtils.isEmpty(info.name) ? null : displayNumber;
    /* phone number type (e.g. mobile) in second line of contact view */
    contact.numberLabel = numberType;
    return contact;
  }

  private void logCallLogAction(int id) {
    if (id == R.id.send_message_action) {
      Logger.get(mContext).logImpression(DialerImpression.Type.CALL_LOG_SEND_MESSAGE);
    } else if (id == R.id.add_to_existing_contact_action) {
      Logger.get(mContext).logImpression(DialerImpression.Type.CALL_LOG_ADD_TO_CONTACT);
    } else if (id == R.id.create_new_contact_action) {
      Logger.get(mContext).logImpression(DialerImpression.Type.CALL_LOG_CREATE_NEW_CONTACT);
    }
  }

  private void maybeShowBlockNumberMigrationDialog(BlockedNumbersMigrator.Listener listener) {
    if (!FilteredNumberCompat.maybeShowBlockNumberMigrationDialog(
        mContext, ((Activity) mContext).getFragmentManager(), listener)) {
      listener.onComplete();
    }
  }

  private void updateBlockReportActions(boolean isVoicemailNumber) {
    // Set block/spam actions.
    blockReportView.setVisibility(View.GONE);
    blockView.setVisibility(View.GONE);
    unblockView.setVisibility(View.GONE);
    reportNotSpamView.setVisibility(View.GONE);
    String e164Number = PhoneNumberUtils.formatNumberToE164(number, countryIso);
    if (isVoicemailNumber
        || !FilteredNumbersUtil.canBlockNumber(mContext, e164Number, number)
        || !FilteredNumberCompat.canAttemptBlockOperations(mContext)) {
      return;
    }
    boolean isBlocked = blockId != null;
    if (isBlocked) {
      unblockView.setVisibility(View.VISIBLE);
    } else {
      if (isSpamFeatureEnabled) {
        if (isSpam) {
          blockView.setVisibility(View.VISIBLE);
          reportNotSpamView.setVisibility(View.VISIBLE);
        } else {
          blockReportView.setVisibility(View.VISIBLE);
        }
      } else {
        blockView.setVisibility(View.VISIBLE);
      }
    }
  }

  public void setDetailedPhoneDetails(CallDetailsEntries callDetailsEntries) {
    this.callDetailsEntries = callDetailsEntries;
  }

  @VisibleForTesting
  public CallDetailsEntries getDetailedPhoneDetails() {
    return callDetailsEntries;
  }

  public interface OnClickListener {

    void onBlockReportSpam(
        String displayNumber,
        String number,
        String countryIso,
        int callType,
        int contactSourceType);

    void onBlock(
        String displayNumber,
        String number,
        String countryIso,
        int callType,
        int contactSourceType);

    void onUnblock(
        String displayNumber,
        String number,
        String countryIso,
        int callType,
        int contactSourceType,
        boolean isSpam,
        Integer blockId);

    void onReportNotSpam(
        String displayNumber,
        String number,
        String countryIso,
        int callType,
        int contactSourceType);
  }
}
