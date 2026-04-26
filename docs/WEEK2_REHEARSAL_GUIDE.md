# Smart Freight Inbox — Week 2 Rehearsal Guide

## Week 2, Days 10–11: Full Dry-Run Rehearsal (20–25 min)

**Purpose:** Simulate live defense presentation start-to-finish  
**Duration:** 3–4 hours (including setup, rehearsal, feedback)  
**Outcome:** Timing breakdown, transition practice, confidence building  

---

## Rehearsal Setup (2 hours before rehearsal)

### Pre-Rehearsal Checklist

- [ ] Docker Compose running (all services up)
- [ ] Presentation slides ready (PowerPoint or Marp)
- [ ] Demo machine prepared (browser tabs open, docker logs visible)
- [ ] Optional: Video camera or phone to record yourself
- [ ] Optional: 1–2 audience members (friends, mentors) to provide feedback
- [ ] Timer ready (phone or external stopwatch)
- [ ] Script printed (demo runbook, talking points)
- [ ] Water bottle nearby
- [ ] Comfortable chair, clear desk space

### Tech Check (15 min)

Before rehearsal starts:

```bash
# Test browser screen sharing (if presenting remotely)
# Open Zoom/Teams/Google Meet and test sharing desktop

# Verify projector/external monitor (if in-person)
# Resolution: 1920x1080 minimum
# Brightness: Readable from 10 feet away

# Test audio (microphone)
# Speak into mic, check volume levels
```

---

## Rehearsal Structure (40–45 minutes)

**Total presentation + demo time: 20–25 minutes**  
**Q&A buffer: 10–15 minutes**  
**Setup + transitions: 5 minutes**

### Segment 1: Slides 1–4 (Problem & Solution)

**Content:** Problem statement, solution overview, business impact  
**Expected time:** 3–4 minutes

**Talking points (practice these out loud):**
- "Port companies receive hundreds of emails daily..."
- "Today, that's manual. Tomorrow, it's automated."
- "Our system saves 80% of operator time..."

**Actions:**
1. Present slides
2. Watch timer
3. If running fast: add examples or pause for effect
4. If running slow: skip less important details, keep core message

**Checkpoint:**
- [ ] Under 4 minutes
- [ ] Core message is clear
- [ ] Audience engaged (eye contact, confident tone)

---

### Segment 2: Slides 5–7 (Tech & Architecture)

**Content:** Tech stack, v1 overview, v2 roadmap  
**Expected time:** 2.5–3 minutes

**Key animations/transitions:**
- Slide 5 (stack) → List technology names and logos
- Slide 6 (v1 architecture) → Show layered diagram
- Slide 7 (v2) → Show evolutionary path

**Practice:**
- Explain tech choices briefly ("Why Spring Boot? Maturity, speed, ecosystem...")
- Don't get bogged down in details (deep-dive slides are backup)

**Checkpoint:**
- [ ] 2.5–3 minutes
- [ ] Visuals are clear from distance
- [ ] Transitions smooth

---

### Segment 3: Slides 8–11 (AI, Features, DB, Security)

**Content:** Qwen/Llama, key features, database, auth  
**Expected time:** 2.5–3 minutes

**Emphasis:**
- Show fallback mechanisms (resilience theme)
- Highlight human-in-the-loop (key differentiator)
- On-premise by design (GDPR advantage)

**Checkpoint:**
- [ ] 2.5–3 minutes
- [ ] Backup slide concepts ready if audience asks
- [ ] Technical depth appropriate for audience

---

### Segment 4: Slide 12 (Demo Preview)

**Content:** "Here's what you'll see next"  
**Expected time:** 30 seconds

**Actions:**
1. Show slide with demo steps numbered
2. Transition to screen share/desktop
3. Open browser to Thymeleaf login page

**Checkpoint:**
- [ ] Smooth transition to live demo
- [ ] No lag (have slides + desktop both ready)

---

### Segment 5: LIVE DEMO (5–7 minutes)

**Content:** Follow DEFENSE_DEMO_RUNBOOK.md steps 1–8  
**Expected time:** 5–7 minutes

**Steps:**
1. Login (30 sec)
2. Ticket list (30 sec)
3. Send test email (1.5–2 min) ← longest step
4. Refresh & show new ticket (30 sec)
5. Ticket detail (1 min) ← emphasize AI results
6. Approve action (30 sec)
7. Retry logic (optional, 30 sec)
8. Metrics (optional, 30 sec)

**Practice points:**
- Have terminal with `docker compose logs` ready (shows processing in real-time)
- Narrate as you go ("The system is extracting the container number now...")
- Point to key UI elements (container number field, draft text, status button)
- Be ready to skip optional steps if running over

**Checkpoint:**
- [ ] Demo takes 5–7 minutes (not 15+)
- [ ] Narration is clear
- [ ] All systems responsive
- [ ] Have fallback video ready

---

### Segment 6: Back to Slides 13–15 (Challenges, Achievements, Roadmap)

**Content:** What we overcame, what we built, what's next  
**Expected time:** 2–2.5 minutes

**Transition:**
1. Switch from desktop back to slides (practice this!)
2. Click to Slide 13
3. Continue presenting

**Talking points:**
- "We faced some real challenges... here's how we solved them"
- "The system is production-ready for demo. For production use, we have a clear roadmap..."
- "v2 adds horizontal scaling, multi-tenancy, enterprise features..."

**Checkpoint:**
- [ ] 2–2.5 minutes
- [ ] Smooth transition from demo back to slides
- [ ] Confidence in discussing challenges

---

### Segment 7: Slide 16 (Q&A Intro)

**Content:** Thank you, contact info, "Questions?"  
**Expected time:** 30 seconds

**Actions:**
1. Show final slide
2. Invite questions
3. Pause, look confident
4. Wait for first question (don't rush)

**Checkpoint:**
- [ ] Relaxed, confident closing
- [ ] Ready to handle Q&A

---

### Q&A Rehearsal (10–15 minutes)

**If you have 1–2 audience members:**

Ask them to ask 2–3 questions from the Q&A list:

Sample questions:
- "How does it handle 1000 emails/day?"
- "What if the LLM makes a mistake?"
- "Is this GDPR-safe?"
- "How much does it cost to run?"
- "Can it integrate with our email system?"

**For each question:**
1. Pause 2–3 seconds (think)
2. Give your answer (refer to Q&A talking points)
3. Check if they need clarification
4. Move to next question

**Checkpoint:**
- [ ] Answered Q1 confidently
- [ ] Answered Q2 with fewer hesitations
- [ ] Answered Q3 with references (backup slides, docs)

---

## Timing Breakdown Template

**Fill this out during rehearsal:**

```
Presentation Timing Breakdown

Segment 1 (Problem & Solution): _____ min (target: 3–4 min)
Segment 2 (Tech): _____ min (target: 2.5–3 min)
Segment 3 (Features): _____ min (target: 2.5–3 min)
Segment 4 (Demo Preview): _____ min (target: 0.5 min)
── Live Demo ──
  - Login: _____ sec (target: 30 sec)
  - Ticket list: _____ sec (target: 30 sec)
  - Send email: _____ sec (target: 1.5–2 min)
  - New ticket: _____ sec (target: 30 sec)
  - Detail page: _____ sec (target: 1 min)
  - Approve: _____ sec (target: 30 sec)
  - (Optional) Retry logic: _____ sec
  - (Optional) Metrics: _____ sec
  Total demo: _____ min (target: 5–7 min)
──────────────
Segment 6 (Challenges & Roadmap): _____ min (target: 2–2.5 min)
Segment 7 (Closing): _____ min (target: 0.5 min)

TOTAL: _____ min (target: 20–25 min)
Buffer for transitions: _____ min

Notes:
- Slowest step: ______________ (possible bottleneck?)
- Fastest step: ______________ (can add more detail?)
- Smoothest transition: ________
- Roughest transition: ________
```

---

## Feedback Form (For Audience)

If rehearsing with friends/mentors, give them this form:

```
Rehearsal Feedback Form

Presenter: ____________________
Date: ____________________

1. CLARITY
   [ ] Message is clear and easy to follow
   [ ] Technical content at appropriate level (not too deep, not too shallow)
   [ ] Examples help understanding
   Comments: ____________________

2. ENGAGEMENT
   [ ] Speaker maintains eye contact
   [ ] Energy level is good (not monotone)
   [ ] Pacing is comfortable (not rushed, not dragging)
   [ ] Transitions between slides are smooth
   Comments: ____________________

3. DEMO
   [ ] Live demo runs smoothly
   [ ] Narration during demo is clear
   [ ] No technical glitches
   [ ] Demo emphasizes key features
   Comments: ____________________

4. QUESTIONS ANSWERED
   [ ] Presenter listens fully before answering
   [ ] Answers are clear and relevant
   [ ] Presenter admits unknowns gracefully
   [ ] Uses backup slides effectively
   Comments: ____________________

5. OVERALL IMPRESSION
   [ ] Presenter is well-prepared
   [ ] Content is impressive and convincing
   [ ] I would invest/hire/collaborate with this project
   [ ] Room for improvement: ____________________

Suggestions for improvement:
_________________________________
_________________________________
_________________________________

Best part of presentation:
_________________________________
```

---

## Problem-Solving Drills

Practice handling these scenarios during rehearsal:

### Scenario 1: Demo Fails (Ollama Timeout)

**Situation:** You try to send test email, AI processing takes >15 sec.

**What to do:**
1. Say: "Ollama is taking longer than usual. This happens when models are loading. In production, we cache results to avoid this. Let me show you a recording of this workflow running smoothly."
2. Click to pre-recorded demo video
3. Narrate the video as if live
4. Resume with slides after video

**Practice:** Memorize this exact wording so it sounds natural.

### Scenario 2: Browser Won't Load

**Situation:** `http://localhost:8080/login` shows "Connection refused"

**What to do:**
1. Say: "Let me check the backend logs."
2. Show docker logs in terminal
3. If backend is down, say: "Let me restart the service quickly."
4. Restart: `docker compose restart backend && sleep 30`
5. During wait, talk about system architecture or take a Q&A question
6. Try again

**Practice:** Have this rescue plan memorized.

### Scenario 3: Audience Asks Complex Q

**Situation:** "Can you explain optimistic locking and how you avoid lost updates?"

**What to do:**
1. Pause, don't panic
2. Say: "Great question! That's actually in one of my backup slides."
3. Navigate to Backup Slide 5 (or verbally answer)
4. Explain with confidence (refer to Q&A talking points)

**Practice:** Have backup slide references memorized.

### Scenario 4: You Forget What to Say

**Situation:** Brain freeze mid-sentence

**What to do:**
1. Pause (2–3 seconds)
2. Take a sip of water
3. Say: "Let me rephrase that..."
4. Start over with simpler words
5. Keep going

**Practice:** This happens to everyone. Practicing will reduce frequency.

---

## Self-Recording Tips (Optional)

If recording yourself to review:

**Setup:**
- Phone or webcam on tripod
- Frame: Head + shoulders + some hand motion
- Lighting: Face well-lit, no strong backlight
- Audio: Quiet room, microphone close

**Recording:**
- Record entire presentation (20–25 min)
- Watch it back at 1.5x speed
- Note:
  - Filler words ("um", "uh", "like")
  - Long pauses or stumbles
  - Unclear explanations
  - Rushed sections

**Improvement:**
- Re-record problem sections
- Practice until comfortable

---

## Day-Before Checklist

**Do this on Day 10 evening (before Day 11 final run):**

- [ ] Slides finalized and proofread
- [ ] Presentation file backed up (PDF + PowerPoint)
- [ ] Demo environment stable (Docker up, no errors)
- [ ] Browser bookmarks ready (5 tabs open)
- [ ] Timer tested
- [ ] Microphone tested (if remote)
- [ ] Screen sharing tested (if remote)
- [ ] Projector/monitor tested (if in-person)
- [ ] Demo script printed
- [ ] Q&A talking points printed
- [ ] Backup deep-dive slides ready
- [ ] Video fallback recorded and tested
- [ ] Water bottle filled
- [ ] Get 8+ hours sleep

---

## Sign-Off

Print this and check off:

```
Week 2, Days 10–11: Rehearsal Sign-Off

Date: ____________________
Presenter: ____________________

FULL REHEARSAL COMPLETED
Total presentation time: _____ min
Target achieved (20–25 min): [Yes / No]

DEMO REHEARSAL COMPLETED
Demo runs smoothly: [Yes / No]
All steps executed: [Yes / No]
Fallback plan ready: [Yes / No]

QUESTION REHEARSAL COMPLETED
Attempted Q&A: [Yes / No]
Number of questions handled: _____
Confidence level: [Low / Medium / High]

PROBLEM DRILLS COMPLETED
Scenario 1 (Demo Fail): [Practiced / Skip]
Scenario 2 (Browser Fail): [Practiced / Skip]
Scenario 3 (Complex Q): [Practiced / Skip]
Scenario 4 (Brain Freeze): [Practiced / Skip]

READY FOR DEFENSE: [YES / NO]

Issues identified: ____________________
Fixes applied: ____________________
Remaining risks: ____________________

Signature: ____________________
```

---

*Rehearsal Guide prepared: 2026-04-26*  
*Ready for Week 2, Days 10–11*
