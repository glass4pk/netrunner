(in-ns 'game.core)

(declare is-scored?)

(def cards-agendas

  {"15 Minutes"
     {:abilities [{:cost [:click 1] :msg "shuffle 15 Minutes into R&D"
                   :label "Shuffle 15 Minutes into R&D"
                   :effect (req (let [corp-agendas (get-in corp [:scored])
                                      agenda-owner (if (some #(= (:cid %) (:cid card)) corp-agendas) :corp :runner)]
                                  (gain-agenda-point state agenda-owner (- (:agendapoints card))))
                                ; refresh agendapoints to 1 before shuffle in case it was modified by e.g. The Board
                                (move state :corp (dissoc (assoc card :agendapoints 1) :seen :rezzed) :deck {:front true})
                                (shuffle! state :corp :deck))}]
      :flags {:has-abilities-when-stolen true}}

   "Accelerated Beta Test"
   (letfn [(abt [n i]
             {:req (req (pos? i))
              :prompt "Select a piece of ICE from the Temporary Zone to install"
              :choices {:req #(and (:side % "Corp")
                                   (ice? %)
                                   (= (:zone %) [:play-area]))}
              :effect (req (corp-install state side target nil
                                         {:no-install-cost true :install-state :rezzed-no-cost})
                           (trigger-event state side :rez target)
                           (if (< n i)
                             (continue-ability state side (abt (inc n) i) card nil)
                             (effect-completed state side eid card)))})]
     {:optional {:prompt "Look at the top 3 cards of R&D?"
                 :yes-ability {:effect (req (let [n (count (filter ice? (take 3 (:deck corp))))]
                                              (continue-ability state side
                                                                {:msg "look at the top 3 cards of R&D"
                                                                 :effect (req (doseq [c (take 3 (:deck corp))]
                                                                                (move state side c :play-area))
                                                                              (resolve-ability state side (abt 1 n) card nil))}
                                                                card nil)))}}})

   "Advanced Concept Hopper"
   {:events
    {:run
     {:req (req (first-event state side :run))
      :effect (effect (show-wait-prompt :runner "Corp to use Advanced Concept Hopper")
                      (continue-ability
                        {:player :corp
                         :prompt "Use Advanced Concept Hopper to draw 1 card or gain 1 [Credits]?" :once :per-turn
                         :choices ["Draw 1 card" "Gain 1 [Credits]" "No action"]
                         :effect (req (case target
                                        "Gain 1 [Credits]"
                                        (do (gain state :corp :credit 1)
                                            (system-msg state :corp (str "uses Advanced Concept Hopper to gain 1 [Credits]")))
                                        "Draw 1 card"
                                        (do (draw state :corp)
                                            (system-msg state :corp (str "uses Advanced Concept Hopper to draw 1 card")))
                                        "No action"
                                        (system-msg state :corp (str "doesn't use Advanced Concept Hopper")))
                                      (clear-wait-prompt state :runner)
                                      (effect-completed state side eid card))} card nil))}}}

   "Ancestral Imager"
   {:events {:jack-out {:msg "do 1 net damage"
                        :effect (effect (damage :net 1))}}}

   "AstroScript Pilot Program"
   {:effect (effect (add-counter card :agenda 1))
    :abilities [{:counter-cost [:agenda 1] :msg (msg "place 1 advancement token on "
                                                      (card-str state target))
                 :choices {:req can-be-advanced?}
                 :effect (effect (add-prop target :advance-counter 1 {:placed true}))}]}

   "Award Bait"
   {:access {:choices ["0", "1", "2"]
             :prompt "How many advancement tokens?"
             :effect (req (let [c (Integer/parseInt target)]
                            (continue-ability
                             state side
                             {:choices {:req can-be-advanced?}
                              :msg (msg "place " c " advancement tokens on " (card-str state target))
                              :effect (final-effect (add-prop :corp target :advance-counter c {:placed true}))} card nil)))}}

   "Bifrost Array"
   {:req (req (not (empty? (filter #(not= (:title %) "Bifrost Array") (:scored corp)))))
    :optional {:prompt "Trigger the ability of a scored agenda?"
               :yes-ability {:prompt "Choose an agenda to trigger its \"when scored\" ability"
                             :choices {:req #(and (is-type? % "Agenda")
                                                  (not= (:title %) "Bifrost Array")
                                                  (= (first (:zone %)) :scored)
                                                  (:abilities %))}
                             :msg (msg "trigger the \"when scored\" ability of " (:title target))
                             :effect (effect (continue-ability (card-def target) target nil))}}}

   "Braintrust"
   {:effect (effect (add-counter card :agenda (quot (- (:advance-counter card) 3) 2)))
    :events {:pre-rez-cost {:req (req (ice? target))
                            :effect (req (rez-cost-bonus state side (- (get-in card [:counter :agenda] 0))))}}}

   "Breaking News"
   {:effect (effect (tag-runner :runner 2))
    :msg "give the Runner 2 tags"
    :end-turn {:effect (effect (lose :runner :tag 2))
               :msg "make the Runner lose 2 tags"}}

   "Character Assassination"
   {:prompt "Choose a resource to trash"
    :choices {:req #(and (installed? %)
                         (is-type? % "Resource"))}
    :msg (msg "trash " (:title target))
    :effect (final-effect (trash target {:unpreventable true}))}

   "Chronos Project"
   {:msg "remove all cards in the Runner's Heap from the game"
    :interactive (req true)
    :effect (effect (move-zone :runner :discard :rfg))}

   "Clone Retirement"
   {:msg "remove 1 bad publicity" :effect (effect (lose :bad-publicity 1))
    :stolen {:msg "force the Corp to take 1 bad publicity"
             :effect (effect (gain :corp :bad-publicity 1))}}

   "Corporate War"
   {:msg (msg (if (> (:credit corp) 6) "gain 7 [Credits]" "lose all credits"))
    :effect (req (if (> (:credit corp) 6)
                   (gain state :corp :credit 7) (lose state :corp :credit :all)))}

   "Corporate Sales Team"
   (let [e {:msg "gain 1 [Credit]"  :counter-cost [:credit 1]
            :effect (req (gain state :corp :credit 1)
                         (when (zero? (:credit (:counter card)))
                           (unregister-events state :corp card)))}]
    {:effect (effect (add-counter card :credit 10))
     :events {:runner-turn-begins e
              :corp-turn-begins   e}})

   "Dedicated Neural Net"
     (letfn [(access-hq [cards]
               {:prompt "Select a card to access."
                :player :runner
                :choices [(str "Card from HQ")]
                :effect (req (system-msg state side (str "accesses " (:title (first cards))))
                             (when-completed
                               (handle-access state side [(first cards)])
                               (do (if (< 1 (count cards))
                                     (continue-ability state side (access-hq (next cards)) card nil)
                                     (effect-completed state side eid card)))))})]
       (let [psi-effect
             {:delayed-completion true
              :mandatory true
              :effect (req (if (not-empty (:hand corp))
                             (do (show-wait-prompt state :runner "Corp to select cards in HQ to be accessed")
                                 (continue-ability
                                   state :corp
                                   {:prompt (msg "Select " (access-count state side :hq-access) " cards in HQ for the Runner to access")
                                    :choices {:req #(and (in-hand? %) (card-is? % :side :corp))
                                              :max (req (access-count state side :hq-access))}
                                    :effect (effect (clear-wait-prompt :runner)
                                                    (continue-ability :runner (access-hq (shuffle targets)) card nil))}
                                   card nil))
                             (effect-completed state side eid card)))}]
         {:events {:successful-run {:req (req (= target :hq))
                                    :once :per-turn
                                    :psi {:not-equal {:effect (req (when-not (:replace-access (get-in @state [:run :run-effect]))
                                                                     (swap! state update-in [:run :run-effect]
                                                                            #(assoc % :replace-access psi-effect)))
                                                                   (effect-completed state side eid))}}}}}))

   "Director Haas Pet Project"
   (let [dhelper (fn dpp [n] {:prompt "Select a card to install"
                              :show-discard true
                              :choices {:req #(and (= (:side %) "Corp")
                                                   (not (is-type? % "Operation"))
                                                   (#{[:hand] [:discard]} (:zone %)))}
                              :effect (req (corp-install state side target
                                                         (last (get-remote-names @state))
                                                         {:no-install-cost true})
                                           (if (< n 2)
                                             (continue-ability state side (dpp (inc n)) card nil)
                                             (effect-completed state side eid card)))
                              :msg (msg (corp-install-msg target))})]
     {:optional {:prompt "Create a new remote server?"
                 :yes-ability {:prompt "Select a card to install"
                               :show-discard true
                               :choices {:req #(and (:side % "Corp")
                                                    (not (is-type? % "Operation"))
                                                    (#{[:hand] [:discard]} (:zone %)))}
                               :effect (req (corp-install state side target "New remote"
                                                          {:no-install-cost true})
                                            (continue-ability state side (dhelper 1) card nil))
                               :msg "create a new remote server, installing cards at no cost"}}})

   "Domestic Sleepers"
   {:agendapoints-runner (req (do 0))
    :abilities [{:cost [:click 3] :msg "place 1 agenda counter on Domestic Sleepers"
                 :req (req (not (:counter card)))
                 :effect (effect (gain-agenda-point 1)
                                 (set-prop card :counter {:agenda 1} :agendapoints 1))}]}

   "Eden Fragment"
   {:events {:pre-corp-install
               {:req (req (and (is-type? target "ICE")
                               (empty? (let [cards (map first (turn-events state side :corp-install))]
                                         (filter #(is-type? % "ICE") cards)))))
                :effect (effect (ignore-install-cost true))}
             :corp-install
               {:req (req (and (is-type? target "ICE")
                               (empty? (let [cards (map first (turn-events state side :corp-install))]
                                         (filter #(is-type? % "ICE") cards)))))
                :msg (msg "ignore the install cost of the first ICE this turn")}}}

   "Efficiency Committee"
   {:effect (effect (add-counter card :agenda 3))
    :abilities [{:cost [:click 1] :counter-cost [:agenda 1] :effect (effect (gain :click 2))
                 :msg "gain [Click][Click]"}]}

   "Encrypted Portals"
   {:msg (msg "gain " (reduce (fn [c server]
                                (+ c (count (filter #(and (has-subtype? % "Code Gate")
                                                          (rezzed? %)) (:ices server)))))
                              0 (flatten (seq (:servers corp))))
              " [Credits]")
    :effect (effect (gain :credit
                          (reduce (fn [c server]
                                    (+ c (count (filter #(and (has-subtype? % "Code Gate")
                                                              (rezzed? %)) (:ices server)))))
                                  0 (flatten (seq (:servers corp)))))
                    (update-all-ice))
    :events {:pre-ice-strength {:req (req (has-subtype? target "Code Gate"))
                                :effect (effect (ice-strength-bonus 1 target))}}}

   "Executive Retreat"
   {:effect (effect (add-counter card :agenda 1)
                    (shuffle-into-deck :hand))
    :abilities [{:cost [:click 1] :counter-cost [:agenda 1] :msg "draw 5 cards" :effect (effect (draw 5))}]}

   "Explode-a-palooza"
   {:access {:optional {:prompt "Gain 5 [Credits] with Explode-a-palooza ability?"
                       :yes-ability {:msg "gain 5 [Credits]"
                                     :effect (final-effect (gain :corp :credit 5))}}}}

   "False Lead"
   {:abilities [{:req (req (>= (:click runner) 2)) :msg "force the Runner to lose [Click][Click]"
                 :effect (effect (forfeit card) (lose :runner :click 2))}]}

   "Fetal AI"
   {:access {:delayed-completion true
             :req (req (not= (first (:zone card)) :discard)) :msg "do 2 net damage"
             :effect (effect (damage eid :net 2 {:card card}))}
    :steal-cost-bonus (req [:credit 2])}

   "Firmware Updates"
   {:effect (effect (add-counter card :agenda 3))
    :abilities [{:counter-cost [:agenda 1]
                 :choices {:req #(and (ice? %) (can-be-advanced? %))}
                 :req (req (< 0 (get-in card [:counter :agenda] 0)))
                 :msg (msg "place 1 advancement token on " (card-str state target))
                 :once :per-turn
                 :effect (final-effect (add-prop target :advance-counter 1))}]}

   "Genetic Resequencing"
   {:choices {:req #(= (last (:zone %)) :scored)}
    :msg (msg "add 1 agenda counter on " (:title target))
    :effect (final-effect (add-counter target :agenda 1))}

   "Geothermal Fracking"
   {:effect (effect (add-counter card :agenda 2))
    :abilities [{:cost [:click 1]
                 :counter-cost [:agenda 1]
                 :msg "gain 7 [Credits] and take 1 bad publicity"
                 :effect (effect (gain :credit 7 :bad-publicity 1))}]}

   "Gila Hands Arcology"
   {:abilities [{:cost [:click 2] :effect (effect (gain :credit 3)) :msg "gain 3 [Credits]"}]}

   "Global Food Initiative"
   {:agendapoints-runner (req (do 2))}

   "Glenn Station"
   {:abilities [{:label "Host a card from HQ on Glenn Station" :cost [:click 1]
                 :prompt "Choose a card to host on Glenn Station" :choices (req (:hand corp))
                 :msg "host a card from HQ" :effect (effect (host card target {:facedown true}))}
                {:label "Add a card on Glenn Station to HQ" :cost [:click 1]
                 :prompt "Choose a card on Glenn Station" :choices (req (:hosted card))
                 :msg "add a hosted card to HQ" :effect (effect (move target :hand))}]}

   "Government Contracts"
   {:abilities [{:cost [:click 2] :effect (effect (gain :credit 4)) :msg "gain 4 [Credits]"}]}

   "Government Takeover"
   {:abilities [{:cost [:click 1] :effect (effect (gain :credit 3)) :msg "gain 3 [Credits]"}]}

   "Hades Fragment"
   {:flags {:corp-phase-12 (req (and (not-empty (get-in @state [:corp :discard])) (is-scored? state :corp card)))}
    :abilities [{:prompt "Choose a card to add to the bottom of R&D"
                 :show-discard true
                 :choices {:req #(and (= (:side %) "Corp") (= (:zone %) [:discard]))}
                 :effect (effect (move target :deck))
                 :msg (msg "add " (if (:seen target) (:title target) "a card") " to the bottom of R&D")}]}

   "Helium-3 Deposit"
   {:choices ["0", "1", "2"]
    :prompt "How many power counters?"
    :effect (req (let [c (Integer/parseInt target)]
                   (continue-ability
                     state side
                     {:choices {:req #(< 0 (get-in % [:counter :power] 0))}
                      :msg (msg "add " c " power counters on " (:title target))
                      :effect (final-effect (add-counter target :power c))} card nil)))}

   "High-Risk Investment"
   {:effect (effect (add-counter card :agenda 1))
    :abilities [{:cost [:click 1]
                 :counter-cost [:agenda 1]
                 :msg (msg "gain " (:credit runner) " [Credits]")
                 :effect (effect (gain :credit (:credit runner)))}]}

   "Hostile Takeover"
   {:msg "gain 7 [Credits] and take 1 bad publicity"
    :effect (effect (gain :credit 7 :bad-publicity 1))}

   "Hollywood Renovation"
   {:install-state :face-up
    :events {:advance
             {:req (req (= (:cid card) (:cid target)))
              :effect (req (let [n (if (>= (:advance-counter (get-card state card)) 6) 2 1)]
                             (continue-ability
                              state side
                              {:choices {:req #(and (not= (:cid %) (:cid card))
                                                    (can-be-advanced? %))}
                               :msg (msg "place " n " advancement tokens on "
                                         (card-str state target))
                               :effect (final-effect (add-prop :corp target :advance-counter n {:placed true}))} card nil)))}}}

   "House of Knives"
   {:effect (effect (add-counter card :agenda 3))
    :abilities [{:counter-cost [:agenda 1]
                 :msg "do 1 net damage"
                 :req (req (:run @state))
                 :once :per-run
                 :effect (effect (damage eid :net 1 {:card card}))}]}

   "Improved Protein Source"
   {:msg "make the Runner gain 4 [Credits]"
    :effect (effect (gain :runner :credit 4))
    :stolen {:msg "make the Runner gain 4 [Credits]"
             :effect (effect (gain :runner :credit 4))}}

   "Improved Tracers"
   {:effect (req (update-all-ice state side))
    :events {:pre-ice-strength {:req (req (has-subtype? target "Tracer"))
                                :effect (effect (ice-strength-bonus 1 target))}
             :pre-init-trace {:req (req (has-subtype? target "Tracer"))
                              :effect (effect (init-trace-bonus 1))}}}

   "Labyrinthine Servers"
   {:effect (effect (add-counter card :power 2))
    :abilities [{:counter-cost [:power 1]
                 :effect (effect (prevent-jack-out))
                 :msg "prevent the Runner from jacking out"}]}

   "License Acquisition"
   {:prompt "Choose an asset or upgrade to install from Archives or HQ" :show-discard true
    :msg (msg "install and rez " (:title target))
    :choices {:req #(and (#{"Asset" "Upgrade"} (:type %))
                         (#{[:hand] [:discard]} (:zone %))
                         (= (:side %) "Corp"))}
    :effect (effect (corp-install eid target nil {:install-state :rezzed-no-cost}))}

   "Mandatory Upgrades"
   {:msg "gain an additional [Click] per turn"
    :effect (effect (gain :click 1 :click-per-turn 1))
    :leave-play (req (lose state :corp :click 1 :click-per-turn 1))}

   "Market Research"
   {:req (req tagged)
    :effect (effect (add-counter card :agenda 1)
                    (set-prop card :agendapoints 3))}

   "Medical Breakthrough"
   {:effect (effect (update-all-advancement-costs))
    :stolen {:effect (effect (update-all-advancement-costs))}
    :advancement-cost-bonus (req (- (count (filter #(= (:title %) "Medical Breakthrough")
                                                   (concat (:scored corp) (:scored runner))))))}

   "Merger"
   {:agendapoints-runner (req (do 3))}

   "NAPD Contract"
   {:steal-cost-bonus (req [:credit 4])
    :advancement-cost-bonus (req (+ (:bad-publicity corp)
                                    (:has-bad-pub corp)))}

   "New Construction"
   {:install-state :face-up
    :events {:advance
             {:optional
              {:req (req (= (:cid card) (:cid target)))
               :prompt "Install a card from HQ in a new remote?"
               :yes-ability {:prompt "Choose a card in HQ to install"
                             :choices {:req #(and (not (is-type? % "Operation"))
                                                  (not (is-type? % "ICE"))
                                                  (= (:side %) "Corp")
                                                  (in-hand? %))}
                             :msg (msg "install a card from HQ" (when (>= (:advance-counter (get-card state card)) 5)
                                       " and rez it, ignoring all costs"))
                             :effect (req (if (>= (:advance-counter (get-card state card)) 5)
                                            (do (corp-install state side target "New remote"
                                                              {:install-state :rezzed-no-cost})
                                                (trigger-event state side :rez target))
                                            (corp-install state side target "New remote")))}}}}}

   "Nisei MK II"
   {:effect (effect (add-counter card :agenda 1))
    :abilities [{:req (req (:run @state))
                 :counter-cost [:agenda 1]
                 :msg "end the run"
                 :effect (effect (end-run))}]}

   "Oaktown Renovation"
   {:install-state :face-up
    :events {:advance {:req (req (= (:cid card) (:cid target)))
                       :msg (msg "gain " (if (>= (:advance-counter (get-card state card)) 5) "3" "2") " [Credits]")
                       :effect (req (gain state side :credit
                                          (if (>= (:advance-counter (get-card state card)) 5) 3 2)))}}}

   "Personality Profiles"
   (let [pp {:msg "force the Runner to trash 1 card from their Grip at random"
             :effect (effect (trash (first (shuffle (:hand runner)))))}]
     {:events {:searched-stack pp
               :runner-install (assoc pp :req (req (some #{:discard} (:previous-zone target))))}})

   "Philotic Entanglement"
   {:req (req (> (count (:scored runner)) 0))
    :msg (msg "do " (count (:scored runner)) " net damage")
    :effect (effect (damage eid :net (count (:scored runner)) {:card card}))}

   "Posted Bounty"
   {:optional {:prompt "Forfeit Posted Bounty to give the Runner 1 tag and take 1 bad publicity?"
               :yes-ability {:msg "give the Runner 1 tag and take 1 bad publicity"
                             :effect (final-effect (gain :bad-publicity 1) (tag-runner :runner 1) (forfeit card))}}}

   "Priority Requisition"
   {:choices {:req #(and (ice? %) (not (rezzed? %)))}
    :msg (msg "rez " (:title target) " at no cost")
    :effect (final-effect (rez target {:ignore-cost :all-costs}))}

   "Private Security Force"
   {:abilities [{:req (req tagged) :cost [:click 1] :effect (effect (damage eid :meat 1 {:card card}))
                 :msg "do 1 meat damage"}]}

   "Profiteering"
   {:choices ["0" "1" "2" "3"] :prompt "How many bad publicity?"
    :msg (msg "take " target " bad publicity and gain " (* 5 (Integer/parseInt target)) " [Credits]")
    :effect (final-effect (gain :credit (* 5 (Integer/parseInt target))
                                :bad-publicity (Integer/parseInt target)))}

   "Project Ares"
     {:req (req #(and (> (:advance-counter card) 4) (> (count (all-installed state :runner)) 0)))
      :msg (msg "force the Runner to trash " (- (:advance-counter card) 4) " installed cards and take 1 bad publicity")
      :delayed-completion true
      :effect (req (let [ares card]
                     (continue-ability
                       state :runner
                       {:prompt (msg "Choose " (- (:advance-counter ares) 4) " installed cards to trash")
                        :choices {:max (- (:advance-counter ares) 4) :req #(and (:installed %) (= (:side %) "Runner"))}
                        :effect (final-effect (trash-cards targets)
                                              (system-msg (str "trashes " (join ", " (map :title targets)))))}
                       card nil))
                   (gain state :corp :bad-publicity 1))}

   "Project Atlas"
   {:effect (effect (add-counter card :agenda (max 0 (- (:advance-counter card) 3))))
    :abilities [{:counter-cost [:agenda 1]
                 :prompt "Choose a card"
                 :label "Search R&D and add 1 card to HQ"
                 ;; we need the req or the prompt will still show
                 :req (req (< 0 (get-in card [:counter :agenda] 0)))
                 :msg (msg "add " (:title target) " to HQ from R&D")
                 :choices (req (cancellable (:deck corp) :sorted))
                 :cancel-effect (final-effect (system-msg "cancels the effect of Project Atlas"))
                 :effect (final-effect (move target :hand) (shuffle! :deck))}]}

   "Project Beale"
   {:agendapoints-runner (req (do 2))
    :effect (effect (add-counter card :agenda (quot (- (:advance-counter card) 3) 2))
                    (set-prop card :agendapoints (+ 2 (quot (- (:advance-counter card) 3) 2))))}

   "Project Vitruvius"
   {:effect (effect (add-counter card :agenda (- (:advance-counter card) 3)))
    :abilities [{:counter-cost [:agenda 1]
                 :prompt "Choose a card"
                 :req (req (< 0 (get-in card [:counter :agenda] 0)))
                 :msg (msg "add " (if (:seen target)
                                    (:title target) "an unseen card ") " to HQ from Archives")
                 :choices (req (:discard corp)) :effect (effect (move target :hand))}]}

   "Project Wotan"
   {:effect (effect (add-counter card :agenda 3))
    :abilities [{:counter-cost [:agenda 1]
                 :msg "add an 'End the run' subroutine to the approached ICE"}]}

   "Puppet Master"
   {:events {:successful-run
             {:delayed-completion true
              :effect (req (show-wait-prompt state :runner "Corp to use Puppet Master")
                           (continue-ability
                             state :corp
                             {:prompt "Choose a card to place 1 advancement token on with Puppet Master" :player :corp
                              :choices {:req can-be-advanced?}
                              :cancel-effect (final-effect (clear-wait-prompt :runner))
                              :msg (msg "place 1 advancement token on " (card-str state target))
                              :effect (final-effect (add-prop :corp target :advance-counter 1 {:placed true})
                                                    (clear-wait-prompt :runner))} card nil))}}}

   "Quantum Predictive Model"
   {:steal-req (req (not tagged))
    :access {:req (req tagged)
             :effect (effect (as-agenda card 1))
             :msg "add it to their score area and gain 1 agenda point"}}

   "Rebranding Team"
   {:effect (req (doseq [c (filter #(is-type? % "Asset")
                                   (concat (all-installed state :corp)
                                           (:deck corp)
                                           (:hand corp)
                                           (:discard corp)))]
                   (update! state side (assoc c :subtype
                                              (->> (vec (.split (or (:subtype c) "") " - "))
                                                   (cons "Advertisement")
                                                   distinct
                                                   (join " - "))))))
    :msg "make all assets gain Advertisement"}

   "Remote Data Farm"
   {:msg "increase their maximum hand size by 2"
    :effect (effect (gain :hand-size-modification 2))
    :leave-play (effect (lose :hand-size-modification 2))}

   "Research Grant"
   {:req (req (not (empty? (filter #(= (:title %) "Research Grant") (all-installed state :corp)))))
    :prompt "Choose another installed copy of Research Grant to score"
    :choices {:req #(= (:title %) "Research Grant")}
    :effect (final-effect (score (assoc target :advance-counter (:advancementcost target))))
    :msg (msg "score another copy of Research Grant")}

   "Restructured Datapool"
   {:abilities [{:cost [:click 1]
                 :trace {:base 2 :msg "give the Runner 1 tag" :effect (effect (tag-runner :runner 1))}}]}

   "Self-Destruct Chips"
   {:effect (effect (lose :runner :hand-size-modification 1))
    :leave-play (effect (gain :runner :hand-size-modification 1))}

   "Sentinel Defense Program"
   {:events {:damage {:req (req (= target :brain)) :msg "to do 1 net damage"
                      :effect (effect (damage eid :net 1 {:card card})) }}}

   "Superior Cyberwalls"
   {:msg (msg "gain " (reduce (fn [c server]
                                (+ c (count (filter (fn [ice] (and (has? ice :subtype "Barrier")
                                                                   (:rezzed ice))) (:ices server)))))
                              0 (flatten (seq (:servers corp))))
              " [Credits]")
    :effect (req (do (gain state :corp :credit
                           (reduce (fn [c server]
                                     (+ c (count (filter #(and (has-subtype? % "Barrier")
                                                               (rezzed? %)) (:ices server)))))
                                   0 (flatten (seq (:servers corp)))))
                     (update-all-ice state side)))
    :events {:pre-ice-strength {:req (req (has? target :subtype "Barrier"))
                                :effect (effect (ice-strength-bonus 1 target))}}}

   "TGTBT"
   {:access {:msg "give the Runner 1 tag" :effect (effect (tag-runner :runner 1))}}

   "The Cleaners"
   {:events {:pre-damage {:req (req (= target :meat)) :msg "do 1 additional meat damage"
                          :effect (effect (damage-bonus :meat 1))}}}

   "The Future is Now"
   {:prompt "Choose a card to add to HQ" :choices (req (:deck corp))
    :msg (msg "add a card from R&D to HQ and shuffle R&D")
    :effect (final-effect (move target :hand) (shuffle! :deck))}

   "The Future Perfect"
   {:steal-req (req installed)
    :access {:psi {:req (req (not installed)) :equal {:effect (final-effect (steal card))}}}}

   "Underway Renovation"
   {:install-state :face-up
    :events {:advance {:req (req (= (:cid card) (:cid target)))
                       :msg (msg "trash the top " (if (>= (:advance-counter (get-card state card)) 4) "2 cards" "card")
                                 " of the Runner's Stack")
                       :effect (effect (mill :runner
                                             (if (>= (:advance-counter (get-card state card)) 4) 2 1)))}}}

   "Unorthodox Predictions"
   {:delayed-completion false
    :prompt "Choose an ICE type for Unorthodox Predictions" :choices ["Sentry", "Code Gate", "Barrier"]
    :msg (msg "prevent subroutines on " target " ICE from being broken until next turn.")}

   "Utopia Fragment"
   {:events {:pre-steal-cost {:req (req (pos? (or (:advance-counter target) 0)))
                              :effect (req (let [counter (:advance-counter target)]
                                             (steal-cost-bonus state side [:credit (* 2 counter)])))}}}

   "Veterans Program"
   {:msg "lose 2 bad publicity"
    :effect (effect (lose :bad-publicity 2))}

   "Voting Machine Initiative"
   {:effect (effect (add-counter card :agenda 3))
    :abilities [{:optional {:req (req (> (get-in card [:counter :agenda] 0)
                                         (:vmi-count card 0)))
                            :prompt "Cause the Runner to lose [Click] at the start of their next turn?"
                            :yes-ability {:effect (effect (toast (str "The Runner will lose " (inc (:vmi-count card 0))
                                                                      " [Click] at the start of their next turn") "info")
                                                    (update! (update-in card [:vmi-count] #(inc (or % 0)))))}}}]
    :events {:runner-turn-begins {:req (req (pos? (:vmi-count card 0)))
                                  :msg (msg "to force the Runner to lose " (:vmi-count card) " [Click]")
                                  :effect (effect (lose :runner :click (:vmi-count card))
                                                  (add-counter (dissoc card :vmi-count) :agenda (- (:vmi-count card))))}}}

   "Vulcan Coverup"
   {:msg "do 2 meat damage"
    :effect (effect (damage eid :meat 2 {:card card}))
    :stolen {:msg "force the Corp to take 1 bad publicity"
             :effect (effect (gain :corp :bad-publicity 1))}}})
